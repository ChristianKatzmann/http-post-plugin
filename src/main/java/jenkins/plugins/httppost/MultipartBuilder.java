package jenkins.plugins.httppost;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import okio.BufferedSink;

/**
 * Fluent API to build <a href="http://www.ietf.org/rfc/rfc2387.txt">RFC 2387</a>-compliant request
 * bodies.
 */
// TODO: Hotfixed copy of MultipartBuilder. Safe to be removed with a upcoming 2.0.1 version.
public final class MultipartBuilder {

  /**
   * The media-type multipart/form-data follows the rules of all multipart MIME data streams as
   * outlined in RFC 2046. In forms, there are a series of fields to be supplied by the user who
   * fills out the form. Each field has a name. Within a given form, the names are unique.
   */
  public static final MediaType FORM = MediaType.parse("multipart/form-data");
  private final MediaType type = FORM;
  private final String boundary;
  // Parallel lists of headers and bodies. Headers may be null. Bodies are never null.
  private final List<Headers> partHeaders = new ArrayList<Headers>();
  private final List<RequestBody> partBodies = new ArrayList<RequestBody>();

  /** Creates a new multipart builder that uses a random boundary token. */
  public MultipartBuilder() {
    this(UUID.randomUUID().toString());
  }

  /**
   * Creates a new multipart builder that uses {@code boundary} to separate parts. Prefer the
   * no-argument constructor to defend against injection attacks.
   */
  public MultipartBuilder(String boundary) {
    this.boundary = boundary;
  }

  /**
   * Appends a quoted-string to a StringBuilder.
   *
   * <p>RFC 2388 is rather vague about how one should escape special characters in form-data
   * parameters, and as it turns out Firefox and Chrome actually do rather different things, and
   * both say in their comments that they're not really sure what the right approach is. We go with
   * Chrome's behavior (which also experimentally seems to match what IE does), but if you actually
   * want to have a good chance of things working, please avoid double-quotes, newlines, percent
   * signs, and the like in your field names.
   */
  private static StringBuilder appendQuotedString(StringBuilder target, String key) {
    target.append('"');
    for (int i = 0, len = key.length(); i < len; i++) {
      char ch = key.charAt(i);
      switch (ch) {
        case '\n':
          target.append("%0A");
          break;
        case '\r':
          target.append("%0D");
          break;
        case '"':
          target.append("%22");
          break;
        default:
          target.append(ch);
          break;
      }
    }
    target.append('"');
    return target;
  }

  public MultipartBuilder type(@SuppressWarnings("UnusedParameters") MediaType type) {
    /* No-op */
    return this;
  }

  /** Add a part to the body. */
  public MultipartBuilder addPart(Headers headers, RequestBody body) {
    if (body == null) {
      throw new NullPointerException("body == null");
    }
    if (headers != null && headers.get("Content-Type") != null) {
      throw new IllegalArgumentException("Unexpected header: Content-Type");
    }
    if (headers != null && headers.get("Content-Length") != null) {
      throw new IllegalArgumentException("Unexpected header: Content-Length");
    }
    partHeaders.add(headers);
    partBodies.add(body);
    return this;
  }

  /** Add a form data part to the body. */
  public MultipartBuilder addFormDataPart(String name, String filename, RequestBody value) {
    if (name == null) {
      throw new NullPointerException("name == null");
    }
    StringBuilder disposition = new StringBuilder("form-data; name=");
    appendQuotedString(disposition, name);

    if (filename != null) {
      disposition.append("; filename=");
      appendQuotedString(disposition, filename);
    }

    return addPart(Headers.of("Content-Disposition", disposition.toString()), value);
  }

  /** Assemble the specified parts into a request body. */
  public RequestBody build() {
    if (partHeaders.isEmpty()) {
      throw new IllegalStateException("Multipart body must have at least one part.");
    }
    return new MultipartRequestBody(type, boundary, partHeaders, partBodies);
  }

  private static final class MultipartRequestBody extends RequestBody {
    private final String boundary;
    private final MediaType contentType;
    private final List<Headers> partHeaders;
    private final List<RequestBody> partBodies;

    public MultipartRequestBody(MediaType type, String boundary, List<Headers> partHeaders,
        List<RequestBody> partBodies) {
      if (type == null) throw new NullPointerException("type == null");

      this.boundary = boundary;
      this.contentType = MediaType.parse(type + "; boundary=" + boundary);
      this.partHeaders = Util.immutableList(partHeaders);
      this.partBodies = Util.immutableList(partBodies);
    }

    private static void writeBoundary(BufferedSink sink, byte[] boundary,
        boolean first, boolean last) throws IOException {
      if (!first) {
        sink.writeUtf8("\r\n");
      }
      sink.writeUtf8("--");
      sink.write(boundary);
      if (last) {
        sink.writeUtf8("--");
      }
      sink.writeUtf8("\r\n");
    }

    @Override public MediaType contentType() {
      return contentType;
    }

    @Override public void writeTo(BufferedSink sink) throws IOException {
      byte[] boundary = this.boundary.getBytes("UTF-8");
      boolean first = true;
      for (int i = 0; i < partHeaders.size(); i++) {
        Headers headers = partHeaders.get(i);
        RequestBody body = partBodies.get(i);
        writeBoundary(sink, boundary, first, false);
        writePart(sink, headers, body);
        first = false;
      }
      writeBoundary(sink, boundary, false, true);
    }

    private void writePart(BufferedSink sink, Headers headers, RequestBody body)
        throws IOException {
      if (headers != null) {
        for (int i = 0; i < headers.size(); i++) {
          sink.writeUtf8(headers.name(i))
              .writeUtf8(": ")
              .writeUtf8(headers.value(i))
              .writeUtf8("\r\n");
        }
      }

      MediaType contentType = body.contentType();
      if (contentType != null) {
        sink.writeUtf8("Content-Type: ")
            .writeUtf8(contentType.toString())
            .writeUtf8("\r\n");
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        sink.writeUtf8("Content-Length: ")
            .writeUtf8(Long.toString(contentLength))
            .writeUtf8("\r\n");
      }

      sink.writeUtf8("\r\n");
      body.writeTo(sink);
    }
  }
}