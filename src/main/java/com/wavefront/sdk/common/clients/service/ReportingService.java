package com.wavefront.sdk.common.clients.service;

import com.google.common.annotations.VisibleForTesting;
import com.wavefront.sdk.common.annotation.Nullable;
import com.wavefront.sdk.common.logging.MessageDedupingLogger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

/**
 * ReportingService that reports entities to Proxy or Wavefront services.
 *
 * @author Mike McMahon (mike.mcmahon@wavefront.com)
 */
public class ReportingService implements ReportAPI {

  private static final MessageDedupingLogger log =
      new MessageDedupingLogger(Logger.getLogger(ReportingService.class.getCanonicalName()), 2, 0.2f);

  private final String token;
  private final URI uri;

  private static final int CONNECT_TIMEOUT_MILLIS = 30000;
  private static final int READ_TIMEOUT_MILLIS = 10000;
  private static final int BUFFER_SIZE = 4096;
  private static final int NO_HTTP_RESPONSE = -1;

  public ReportingService(String server, @Nullable String token) {
    this.uri = URI.create(server);
    this.token = token;
  }

  @Override
  public int send(String format, InputStream stream) {
    HttpURLConnection urlConn = null;
    int statusCode = 400;
    try {
      URL url = getReportingUrl(uri, format);
      urlConn = (HttpURLConnection) url.openConnection();
      urlConn.setDoOutput(true);
      urlConn.setRequestMethod("POST");
      urlConn.addRequestProperty("Content-Type", "application/octet-stream");
      urlConn.addRequestProperty("Content-Encoding", "gzip");
      if (token != null && !token.equals("")) {
        urlConn.addRequestProperty("Authorization", "Bearer " + token);
      }
      urlConn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
      urlConn.setReadTimeout(READ_TIMEOUT_MILLIS);

      try (GZIPOutputStream gzipOS = new GZIPOutputStream(urlConn.getOutputStream())) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (stream.available() > 0) {
          gzipOS.write(buffer, 0, stream.read(buffer));
        }
        gzipOS.flush();
      }
      statusCode = urlConn.getResponseCode();
      readAndClose(urlConn.getInputStream());
    } catch (IOException ex) {
      if (urlConn != null) {
        return safeGetResponseCodeAndClose(urlConn);
      }
    }
    return statusCode;
  }

  private int safeGetResponseCodeAndClose(HttpURLConnection urlConn) {
    int statusCode;
    try {
      statusCode = urlConn.getResponseCode();
    } catch (IOException ex) {
      log.log(Level.SEVERE, "Unable to obtain status code from the Wavefront service at "
          + urlConn.getURL().toString(), ex);
      statusCode = NO_HTTP_RESPONSE;
    }

    try {
      readAndClose(urlConn.getErrorStream());
    } catch (IOException ex) {
      log.log(Level.SEVERE, "Unable to read and close error stream from the Wavefront service at "
          + urlConn.getURL().toString(), ex);
    }

    return statusCode;
  }

  private void readAndClose(InputStream stream) throws IOException {
    if (stream != null) {
      try (InputStream is = stream) {
        byte[] buffer = new byte[BUFFER_SIZE];
        // read entire stream before closing
        while (is.read(buffer) > 0) {
        }
      }
    }
  }

  /**
   * For a given URI generate a properly formatted URL suitable
   * for sending data to either proxies or a Wavefront service.
   *
   * @param server a server to report to
   * @param format the format of data to send
   * @return returns as properly formatted URL ending in /report?=format
   * @throws MalformedURLException
   */
  @VisibleForTesting
  public static URL getReportingUrl(URI server, String format) throws MalformedURLException {
    String originalPath = server.getPath() != null ? server.getPath() : "";
    originalPath = originalPath.replaceAll("(\\/){2,}", "/");
    originalPath = originalPath.equals("/") ? "" : originalPath;
    if (originalPath.endsWith("/report/")) {
      originalPath = originalPath.replaceAll("/report/$", "/report");
    } else if (!originalPath.endsWith("/report")) {
      originalPath += "/report";
    }
    URL url = new URL(server.getScheme(), server.getHost(), server.getPort(), originalPath + "?f=" + format);
    return url;
  }
}
