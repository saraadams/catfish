package de.ofahrt.catfish.client;

import java.io.IOException;

public class ConnectionClosedException extends IOException {
  private static final long serialVersionUID = 1L;

  public ConnectionClosedException(String msg) {
    super(msg);
  }
}