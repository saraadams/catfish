package de.ofahrt.catfish.internal.network;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.network.NetworkServer;

public final class NetworkEngine {
  private static final boolean DEBUG = false;
  private static final boolean LOG_TO_FILE = false;

  private static final boolean OUTGOING_CONNECTION = true;
  private static final boolean INCOMING_CONNECTION = false;

  public enum FlowState {
    CONTINUE,
    PAUSE,
    HALF_CLOSE,
    SLOW_CLOSE,
    CLOSE;
  }

  public interface Stage {
    FlowState read() throws IOException;
    void inputClosed() throws IOException;
    FlowState write() throws IOException;
    void close();
  }

  public interface Pipeline {
    Connection getConnection();
    void encourageWrites();
    void encourageReads();
    void close();
    void queue(Runnable runnable);
    void log(String text, Object... params);
  }

  public interface NetworkHandler {
    boolean usesSsl();
    Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  private interface LogHandler {
    void log(String text);
  }

  private final static class FileLogHandler implements LogHandler {
    private static final String POISON_PILL = "poison pill";

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
    private final PrintWriter out;

    FileLogHandler(File f) throws IOException {
      out = new PrintWriter(new BufferedOutputStream(new FileOutputStream(f), 10000));
      new Thread(this::run, "log-writer").start();
    }

    @Override
    public void log(String text) {
      try {
        queue.put(text);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void run() {
      try {
        String line;
        while ((line = queue.take()) != POISON_PILL) {
          out.println(line);
        }
        out.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        out.close();
      }
    }
  }

  private final static class ConsoleLogHandler implements LogHandler {
    @Override
    public void log(String text) {
      System.out.println(text);
    }
  }

  private final class SocketHandler implements EventHandler, Pipeline {
    private final SelectorQueue queue;
    private final Connection connection;
    private final SocketChannel socketChannel;
    private final SelectionKey key;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final LogHandler logHandler;

    private final Stage first;
    private final AtomicBoolean active = new AtomicBoolean();
    private FlowState reading = FlowState.PAUSE;
    private FlowState writing = FlowState.PAUSE;
    private boolean connecting;
    private boolean closed;

    SocketHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key,
        NetworkHandler handler,
        LogHandler logHandler,
        boolean outgoing) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.logHandler = logHandler;
      this.inputBuffer = ByteBuffer.allocate(32768);
      this.outputBuffer = ByteBuffer.allocate(32768);
      inputBuffer.clear();
      inputBuffer.flip(); // prepare for reading
      outputBuffer.clear();
      outputBuffer.flip(); // prepare for reading
      this.connecting = outgoing;
      this.reading = outgoing ? FlowState.PAUSE : FlowState.CONTINUE;
      this.first = handler.connect(this, inputBuffer, outputBuffer);
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    @Override
    public void encourageWrites() {
      queue.queue(() -> {
        if (writing == FlowState.HALF_CLOSE || writing == FlowState.CLOSE) {
          return;
        }
        writing = FlowState.CONTINUE;
        handleEvent();
      });
    }

    @Override
    public void encourageReads() {
      queue.queue(() -> {
        if (reading == FlowState.HALF_CLOSE || reading == FlowState.CLOSE) {
          return;
        }
        reading = FlowState.CONTINUE;
        handleEvent();
      });
    }

    @Override
    public void close() {
      if (!active.get()) {
        queue.queue(() -> {
          closed = true;
          handleEvent();
        });
      }
      closed = true;
    }

    @Override
    public void handleEvent() {
      log("connecting=" + connecting + " reading=" + reading + " writing=" + writing + " closed=" + closed);
      active.set(true);
      try {
        if (!closed && connecting) {
          if (key.isConnectable()) {
            log("Connected");
            socketChannel.finishConnect();
            connecting = false;
            reading = FlowState.PAUSE;
            writing = FlowState.CONTINUE;
          } else {
            return;
          }
        }

        if (!closed && reading == FlowState.CONTINUE && key.isReadable()) {
          inputBuffer.compact(); // prepare buffer for writing
          int readCount = socketChannel.read(inputBuffer);
          inputBuffer.flip(); // prepare buffer for reading
          if (readCount == -1) {
            log("Input closed");
            first.inputClosed();
          } else {
            log("Read %d bytes (%d buffered)",
                Integer.valueOf(readCount), Integer.valueOf(inputBuffer.remaining()));
          }
        }
        while (!closed && reading == FlowState.CONTINUE && inputBuffer.hasRemaining()) {
          reading = first.read();
          if (reading == FlowState.SLOW_CLOSE) {
            throw new IllegalStateException(
                String.format("Cannot slow-close after read (%s)", first));
          }
          if (reading == FlowState.CLOSE) {
            closed = true;
            break;
          }
        }
        if (!closed && reading == FlowState.HALF_CLOSE) {
          socketChannel.shutdownInput();
        }

        while (!closed && writing == FlowState.CONTINUE && (available(outputBuffer) > 0)) {
          int before = available(outputBuffer);
          writing = first.write();
          if (writing == FlowState.CLOSE) {
            closed = true;
            break;
          }
          if (before == available(outputBuffer)) {
            break;
          }
        }
        if (!closed && outputBuffer.hasRemaining() && key.isWritable()) {
          int before = outputBuffer.remaining();
          socketChannel.write(outputBuffer);
          log("Wrote %d bytes", Integer.valueOf(before - outputBuffer.remaining()));
          if (outputBuffer.remaining() > 0) {
            outputBuffer.compact(); // prepare for writing
            outputBuffer.flip(); // prepare for reading
          }
        } else if (!closed && writing == FlowState.HALF_CLOSE) {
          socketChannel.shutdownOutput();
        } else if (writing == FlowState.SLOW_CLOSE) {
          closed = true;
        }

        if (!closed) {
          int ops = (reading == FlowState.CONTINUE ? SelectionKey.OP_READ : 0)
              | ((outputBuffer.hasRemaining() || writing == FlowState.CONTINUE) ? SelectionKey.OP_WRITE : 0);
          if (ops != key.interestOps()) {
            log("Changing to: " + ops);
            key.interestOps(ops);
          }
        }
      } catch (IOException e) {
        networkEventListener.notifyInternalError(connection, e);
        closed = true;
      } finally {
        active.set(false);
      }

      if (closed) {
        log("Close");
        // Release resources, we may have a worker thread blocked on writing to the connection.
        first.close();
        closedCounter.incrementAndGet();
        key.cancel();
        try {
          socketChannel.close();
        } catch (IOException ignored) {
          // There's nothing we can do if this fails.
          networkEventListener.notifyInternalError(connection, ignored);
        }
      }
    }

    private int available(ByteBuffer buffer) {
      return buffer.capacity() - buffer.limit();
    }

    @Override
    public void queue(Runnable runnable) {
      queue.queue(runnable);
    }

    @Override
    public void log(String text, Object... params) {
      if (DEBUG) {
        long atNanos = System.nanoTime() - connection.startTimeNanos();
        long atSeconds = TimeUnit.NANOSECONDS.toSeconds(atNanos);
        long nanoFraction = atNanos - TimeUnit.SECONDS.toNanos(atSeconds);
        String printedText = String.format(text, params);
        logHandler.log(
            String.format(
                "%s[%3s.%9d] %s",
                connection,
                Long.valueOf(atSeconds),
                Long.valueOf(nanoFraction),
                printedText));
      }
    }
  }

  private final class ServerSocketHandler implements EventHandler {
    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final NetworkHandler handler;

    public ServerSocketHandler(ServerSocketChannel serverChannel, SelectionKey key, NetworkHandler handler) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.handler = handler;
    }

    @Override
    public void handleEvent() throws IOException {
      if (key.isAcceptable()) {
        @SuppressWarnings("resource")
        SocketChannel socketChannel = serverChannel.accept();
        openCounter.incrementAndGet();
        Connection connection = new Connection(
            (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
            (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress(),
            handler.usesSsl());
        socketChannel.configureBlocking(false);
        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.socket().setSoLinger(false, 0);
        getQueueForConnection().attachConnection(connection, socketChannel, handler);
      }
    }

    public void shutdown() {
      key.cancel();
      try {
        serverChannel.close();
      } catch (IOException ignored) {
        // Not much we can do at this point.
      }
    }
  }

  private final class SelectorQueue implements Runnable {
    private final int id;
    private final Selector selector;
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> shutdownQueue = new LinkedBlockingQueue<>();
    private final LogHandler logHandler;
    private boolean shutdown;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

    public SelectorQueue(int id, LogHandler logHandler) throws IOException {
      this.id = id;
      this.logHandler = logHandler;
      this.selector = Selector.open();
      Thread t = new Thread(this, "catfish-select-" + this.id);
      t.start();
    }

    private void listenPort(final InetAddress address, final int port, final NetworkHandler handler) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(() -> {
        try {
          if (shutdown) {
            return;
          }
          networkEventListener.portOpened(new NetworkServer() {
            @Override
            public InetAddress address() {
              return address;
            }

            @Override
            public int port() {
              return port;
            }

            @Override
            public boolean ssl() {
              return handler.usesSsl();
            }
          });
          @SuppressWarnings("resource")
          ServerSocketChannel serverChannel = ServerSocketChannel.open();
          serverChannel.configureBlocking(false);
          serverChannel.socket().setReuseAddress(true);
          serverChannel.socket().bind(new InetSocketAddress(address, port));
          SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
          ServerSocketHandler socketHandler = new ServerSocketHandler(serverChannel, key, handler);
          key.attach(socketHandler);
          shutdownQueue.add(socketHandler::shutdown);
        } catch (IOException e) {
          thrownException.set(e);
        }
        latch.countDown();
      });
      latch.await();
      IOException e = thrownException.get();
      if (e != null) {
        throw e;
      }
    }

    public void connect(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(() -> {
        try {
          if (shutdown) {
            return;
          }
          @SuppressWarnings("resource")
          SocketChannel socketChannel = SocketChannel.open();
          socketChannel.configureBlocking(false);
//          clientChannel.socket().setReuseAddress(true);
//          clientChannel.socket().bind(new InetSocketAddress(address, port));
          socketChannel.socket().setTcpNoDelay(true);
          socketChannel.socket().setKeepAlive(true);
//          clientChannel.socket().setSoLinger(false, 0);
          InetSocketAddress remoteAddress = new InetSocketAddress(address, port);
          socketChannel.connect(remoteAddress);
          SelectionKey key = socketChannel.register(selector, SelectionKey.OP_CONNECT);
          Connection connection = new Connection(
              (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
              remoteAddress,
              handler.usesSsl());
          SocketHandler socketHandler =
              new SocketHandler(
                  this, connection, socketChannel, key, handler, logHandler, OUTGOING_CONNECTION);
          key.attach(socketHandler);
        } catch (IOException e) {
          thrownException.set(e);
        }
        latch.countDown();
      });
      latch.await();
      IOException e = thrownException.get();
      if (e != null) {
        throw e;
      }
    }

    private void shutdown() throws InterruptedException {
      if (shutdownInitiated.getAndSet(true)) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      shutdownQueue.add(() -> latch.countDown());
      queue(() -> shutdown = true);
      latch.await();
    }

    private void attachConnection(Connection connection, SocketChannel socketChannel, NetworkHandler handler) {
      queue(() -> {
        try {
          SelectionKey socketKey = socketChannel.register(selector, SelectionKey.OP_READ);
          SocketHandler socketHandler =
              new SocketHandler(
                  this, connection, socketChannel, socketKey, handler, logHandler, INCOMING_CONNECTION);
          socketHandler.log("New");
          socketKey.attach(socketHandler);
        } catch (ClosedChannelException e) {
          throw new RuntimeException(e);
        }
      });
    }

    private void queue(Runnable runnable) {
      eventQueue.add(runnable);
      selector.wakeup();
    }

    @Override
    public void run() {
      try {
        while (!shutdown) {
//          if (DEBUG) {
//            System.out.println(
//                "PENDING: " + (openCounter.get() - closedCounter.get()) + " REJECTED " + rejectedCounter.get());
//          }
          selector.select();
//          if (DEBUG) {
//            System.out.printf(
//                "Queue=%d, Keys=%d\n", Integer.valueOf(id), Integer.valueOf(selector.keys().size()));
//          }
          Runnable runnable;
          while ((runnable = eventQueue.poll()) != null) {
            runnable.run();
          }
          for (SelectionKey key : selector.selectedKeys()) {
            EventHandler handler = (EventHandler) key.attachment();
            handler.handleEvent();
          }
          selector.selectedKeys().clear();
        }
        while (!shutdownQueue.isEmpty()) {
          shutdownQueue.remove().run();
        }
      } catch (IOException e) {
        networkEventListener.notifyInternalError(null, e);
      }
    }
  }

  private final NetworkEventListener networkEventListener;

  private final AtomicInteger openCounter = new AtomicInteger();
  private final AtomicInteger closedCounter = new AtomicInteger();

  private final SelectorQueue[] queues;
  private final AtomicInteger connectionIndex = new AtomicInteger();

  public NetworkEngine(NetworkEventListener networkEventListener) throws IOException {
    this.networkEventListener = networkEventListener;
    this.queues = new SelectorQueue[8];
    LogHandler logHandler;
    if (LOG_TO_FILE) {
      logHandler = new FileLogHandler(new File("/tmp/catfish.log"));
    } else {
      logHandler = new ConsoleLogHandler();
    }
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i, logHandler);
    }
  }

  public void listenAll(int port, NetworkHandler handler) throws IOException, InterruptedException {
    listen(null, port, handler);
  }

  public void listenLocalhost(int port, NetworkHandler handler) throws IOException, InterruptedException {
    listen(InetAddress.getLoopbackAddress(), port, handler);
  }

  private void listen(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
    getQueueForConnection().listenPort(address, port, handler);
  }

  public void connect(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
    getQueueForConnection().connect(address, port, handler);
  }

  public void shutdown() throws InterruptedException {
    for (SelectorQueue queue : queues) {
      queue.shutdown();
    }
    networkEventListener.shutdown();
  }

  public int getOpenConnections() {
    return openCounter.get() - closedCounter.get();
  }

  private SelectorQueue getQueueForConnection() {
    int index = mod(connectionIndex.getAndIncrement(), queues.length);
    return queues[index];
  }

  private int mod(int a, int b) {
    return ((a % b) + b) % b;
  }
}
