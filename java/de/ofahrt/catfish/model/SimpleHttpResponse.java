package de.ofahrt.catfish.model;

import java.util.HashMap;
import java.util.Map;

public final class SimpleHttpResponse implements HttpResponse {
  private final HttpVersion version;
  private final int statusCode;
  private final String statusMessage;
  private final Map<String, String> headers;
  private final byte[] content;

  SimpleHttpResponse(Builder builder) {
    this.version = HttpVersion.of(builder.majorVersion, builder.minorVersion);
    this.statusCode = builder.statusCode;
    this.statusMessage =
        builder.reasonPhrase != null ? builder.reasonPhrase : HttpStatusCode.getStatusMessage(statusCode);
    this.headers = new HashMap<>(builder.headers);
    this.content = builder.content;
  }

  @Override
  public HttpVersion getProtocolVersion() {
    return version;
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public String getStatusMessage() {
    return statusMessage;
  }

  @Override
  public HttpHeaders getHeaders() {
    return HttpHeaders.of(headers);
  }

  @Override
  public byte[] getBody() {
    return content;
  }

  public static final class Builder {
    private int majorVersion = 1;
    private int minorVersion = 1;
    private int statusCode;
    private String reasonPhrase;
    private final Map<String, String> headers = new HashMap<>();
    private byte[] content = new byte[0];

    private String errorMessage;

    public SimpleHttpResponse build() throws MalformedResponseException {
      if (errorMessage != null) {
        throw new MalformedResponseException(errorMessage);
      }
      return new SimpleHttpResponse(this);
    }

    public Builder setBadResponse(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder setMajorVersion(int majorVersion) {
      this.majorVersion = majorVersion;
      return this;
    }

    public Builder setMinorVersion(int minorVersion) {
      this.minorVersion = minorVersion;
      return this;
    }

    public Builder setStatusCode(int statusCode) {
      this.statusCode = statusCode;
      return this;
    }

    public Builder setReasonPhrase(String reasonPhrase) {
      this.reasonPhrase = reasonPhrase;
      return this;
    }

    public Builder setBody(byte[] content) {
      this.content = content;
      return this;
    }

    public Builder addHeader(String key, String value) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(value);
      key = HttpHeaderName.canonicalize(key);
      if (headers.get(key) != null) {
        if (!HttpHeaderName.mayOccurMultipleTimes(key)) {
          setBadResponse("Illegal message headers: multiple occurence for non-list field");
          throw new IllegalArgumentException("Illegal message headers: multiple occurence for non-list field");
        }
        value = headers.get(key) + ", " + value;
      }
      if (HttpHeaderName.HOST.equals(key)) {
        if (!HttpHeaderName.validHostPort(value)) {
          setBadResponse("Illegal 'Host' header");
          throw new IllegalArgumentException("Illegal 'Host' header");
        }
      }
      headers.put(key, value);
      return this;
    }

    public String getHeader(String name) {
      return headers.get(name);
    }
  }
}
