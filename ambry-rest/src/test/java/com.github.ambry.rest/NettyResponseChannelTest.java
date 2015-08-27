package com.github.ambry.rest;

import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import org.json.JSONException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;


/**
 * Tests functionality of {@link NettyResponseChannel}.
 * <p/>
 * To examine functionality of each URI, refer to {@link MockNettyMessageProcessor#handleRequest(HttpRequest)} and
 * {@link MockNettyMessageProcessor#handleContent(HttpContent)}
 */
public class NettyResponseChannelTest {
  /**
   * Tests the common workflow of the {@link NettyResponseChannel} i.e., add some content to response body via
   * {@link NettyResponseChannel#write(ByteBuffer)} and then call {@link NettyResponseChannel#flush()}
   * <p/>
   * For a description of what different URIs do, check {@link TestingUri}. For the actual functionality, check
   * {@link MockNettyMessageProcessor}).
   * @throws IOException
   * @throws JSONException
   */
  @Test
  public void responseChannelCommonCaseTest()
      throws IOException, JSONException {
    String content = "@@randomContent@@@";
    String lastContent = "@@randomLastContent@@@";
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    EmbeddedChannel channel = new EmbeddedChannel(processor);
    channel.writeInbound(createRequest(HttpMethod.GET, "/"));
    channel.writeInbound(createContent(content, false));
    channel.writeInbound(createContent(lastContent, true));

    // first outbound has to be response.
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    // content echoed back.
    String returnedContent = getContentString((HttpContent) channel.readOutbound());
    assertEquals("Content does not match with expected content", content, returnedContent);
    // last content echoed back.
    returnedContent = getContentString((HttpContent) channel.readOutbound());
    assertEquals("Content does not match with expected content", lastContent, returnedContent);
    assertFalse("Channel not closed on the server", channel.isActive());
  }

  /**
   * Tests that the right exceptions are thrown on bad input to the various functions of {@link NettyResponseChannel}.
   * @throws IOException
   */
  @Test
  public void responseChannelExceptionsTest()
      throws JSONException {
    try {
      MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
      EmbeddedChannel channel = new EmbeddedChannel(processor);
      channel.writeInbound(createRequest(HttpMethod.GET, TestingUri.WriteWithDirectBuffer.toString()));
    } catch (IllegalArgumentException e) {
      // expected. nothing to do.
    }
  }

  /**
   * Checks the case where no body needs to be returned but just a
   * {@link NettyResponseChannel#onRequestComplete(Throwable, boolean)} is called on the server. This should return just
   * response metadata.
   * @throws JSONException
   */
  @Test
  public void responseChannelNoBodyTest()
      throws JSONException {
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    EmbeddedChannel channel = new EmbeddedChannel(processor);
    channel.writeInbound(createRequest(HttpMethod.GET, TestingUri.ImmediateRequestComplete.toString()));
    // There should be a response.
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    // Channel should be closed.
    assertFalse("Channel not closed on the server", channel.isActive());
  }

  /**
   * Checks {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} with a valid {@link RestServiceException}
   * and with a null exception.
   * @throws JSONException
   */
  @Test
  public void onRequestCompleteWithExceptionTest()
      throws JSONException {
    // Throws RestServiceException. There should be a response which is BAD_REQUEST. This is the expected response.
    doOnRequestCompleteWithExceptionTest(TestingUri.OnRequestCompleteWithRseBr, HttpResponseStatus.BAD_REQUEST);

    // INTERNAL_SERVER_ERROR
    doOnRequestCompleteWithExceptionTest(TestingUri.OnRequestCompleteWithRseIse,
        HttpResponseStatus.INTERNAL_SERVER_ERROR);

    // Unknown RestServiceException.
    doOnRequestCompleteWithExceptionTest(TestingUri.OnRequestCompleteWithRseUnknown,
        HttpResponseStatus.INTERNAL_SERVER_ERROR);

    // Runtime exception.
    doOnRequestCompleteWithExceptionTest(TestingUri.OnRequestCompleteWithRuntimeException,
        HttpResponseStatus.INTERNAL_SERVER_ERROR);
  }

  /**
   * Performs bad state transitions and verifies that they throw the right exceptions.
   * @throws JSONException
   */
  @Test
  public void badStateTransitionsTest()
      throws Exception {
    // write after close.
    doBadStateTransitionTest(TestingUri.WriteAfterClose, ClosedChannelException.class, null);

    // modify response data after it has been written to the channel
    doBadStateTransitionTest(TestingUri.ModifyResponseMetadataAfterWrite, RestServiceException.class,
        RestServiceErrorCode.IllegalResponseMetadataStateTransition);
  }

  /**
   * Tests that no exceptions are thrown on repeating idempotent operations. Does <b><i>not</i></b> currently test that
   * state changes are idempotent.
   * @throws JSONException
   */
  @Test
  public void idempotentOperationsTest()
      throws JSONException {
    doIdempotentOperationsTest(TestingUri.MultipleClose);
    doIdempotentOperationsTest(TestingUri.MultipleOnRequestComplete);
  }

  /**
   * Tests behaviour of various functions of {@link NettyResponseChannel} under write failures.
   * @throws Exception
   */
  @Test
  public void behaviourUnderWriteFailuresTest()
      throws Exception {
    onRequestCompleteUnderWriteFailureTest(TestingUri.ImmediateRequestComplete);
    onRequestCompleteUnderWriteFailureTest(TestingUri.OnRequestCompleteWithRseBr);

    try {
      String content = "@@randomContent@@@";
      MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
      ChannelOutboundHandler badOutboundHandler = new BadOutboundHandler();
      EmbeddedChannel channel = new EmbeddedChannel(badOutboundHandler, processor);
      channel.writeInbound(createRequest(HttpMethod.GET, "/"));
      // channel gets closed because of write failure
      channel.writeInbound(createContent(content, true));
    } catch (Exception e) {
      if (!(e instanceof ClosedChannelException)) {
        throw e;
      }
    }
  }

  /**
   * Tests the {@link ChannelWriteResultListener}. Currently tests for reactions to bad input, bad state transitions and
   * write failures.
   */
  @Test
  public void channelWriteResultListenerTest() {
    ChannelWriteResultListener listener = new ChannelWriteResultListener(new NettyMetrics(new MetricRegistry()));
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    Channel channel = new EmbeddedChannel(processor);
    assertTrue("Channel is not open", channel.isOpen());
    DefaultChannelPromise future = new DefaultChannelPromise(channel);

    // operationComplete() for future not being tracked - should not throw exceptions.
    listener.operationComplete(future);
    // track future
    listener.trackWrite(future);
    // try to re-track future - should not throw exceptions
    listener.trackWrite(future);
    // mark future as failed and verify that channel is closed.
    future.setFailure(new Exception("placeHolderException"));
    assertFalse("Channel is still open after failed write", channel.isOpen());
  }

  // helpers
  // general
  private HttpRequest createRequest(HttpMethod httpMethod, String uri)
      throws JSONException {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpMethod, uri);
  }

  private HttpContent createContent(String content, boolean isLast) {
    ByteBuf buf = Unpooled.copiedBuffer(content.getBytes());
    if (isLast) {
      return new DefaultLastHttpContent(buf);
    } else {
      return new DefaultHttpContent(buf);
    }
  }

  /**
   * Converts the content in {@link HttpContent} to a human readable string.
   * @param httpContent the content that needs to be converted to a human readable string.
   * @return {@code httpContent} as a human readable string.
   * @throws IOException
   */
  private String getContentString(HttpContent httpContent)
      throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    httpContent.content().readBytes(out, httpContent.content().readableBytes());
    return out.toString("UTF-8");
  }

  // onRequestCompleteWithExceptionTest() helpers

  /**
   * Creates a channel and send the request to the {@link EmbeddedChannel}. Checks the response for the expected
   * status code.
   * @param uri the uri to hit.
   * @param expectedResponseStatus the response status that is expected
   * @throws JSONException
   */
  private void doOnRequestCompleteWithExceptionTest(TestingUri uri, HttpResponseStatus expectedResponseStatus)
      throws JSONException {
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    EmbeddedChannel channel = new EmbeddedChannel(processor);
    channel.writeInbound(createRequest(HttpMethod.GET, uri.toString()));

    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", expectedResponseStatus, response.getStatus());
    // Channel should be closed.
    assertFalse("Channel not closed on the server", channel.isActive());
  }

  // badStateTransitionsTest() helpers

  /**
   * Creates a channel and sends the request to the {@link EmbeddedChannel}. Checks for an exception and verifies the
   * exception class matches. If {@code exceptionClass} is {@link RestServiceException}, then checks the provided
   * {@link RestServiceErrorCode}.
   * @param uri the uri to hit.
   * @param exceptionClass the class of the exception expected.
   * @param expectedCode if {@code exceptionClass} is {@link RestServiceException}, the expected
   *                      {@link RestServiceErrorCode}.
   * @throws JSONException
   */
  private void doBadStateTransitionTest(TestingUri uri, Class exceptionClass, RestServiceErrorCode expectedCode)
      throws Exception {
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    EmbeddedChannel channel = new EmbeddedChannel(processor);
    try {
      channel.writeInbound(createRequest(HttpMethod.GET, uri.toString()));
      fail("This test was expecting the handler in the channel to throw a RestServiceException with error code "
          + expectedCode);
    } catch (Exception e) {
      if (exceptionClass.isInstance(e)) {
        if (exceptionClass.equals(RestServiceException.class)) {
          assertEquals("Unexpected RestServiceErrorCode", expectedCode, ((RestServiceException) e).getErrorCode());
        }
      } else {
        throw e;
      }
    }
  }

  // idempotentOperationsTest() helpers

  /**
   * Checks that idempotent operations do not throw exceptions when called multiple times. Does <b><i>not</i></b>
   * currently test that state changes are idempotent.
   * @param uri the uri to be hit.
   * @throws JSONException
   */
  private void doIdempotentOperationsTest(TestingUri uri)
      throws JSONException {
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    EmbeddedChannel channel = new EmbeddedChannel(processor);
    // no exceptions.
    channel.writeInbound(createRequest(HttpMethod.GET, uri.toString()));
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
  }

  /**
   * Checks that no exceptions are thrown by {@link NettyResponseChannel#onRequestComplete(Throwable, boolean)} when
   * there are write failures.
   * @param uri the uri to hit.
   * @throws JSONException
   */
  private void onRequestCompleteUnderWriteFailureTest(TestingUri uri)
      throws JSONException {
    MockNettyMessageProcessor processor = new MockNettyMessageProcessor();
    BadOutboundHandler badOutboundHandler = new BadOutboundHandler();
    EmbeddedChannel channel = new EmbeddedChannel(badOutboundHandler, processor);
    // no exception because onRequestComplete() swallows it.
    channel.writeInbound(createRequest(HttpMethod.GET, uri.toString()));
  }
}

enum TestingUri {
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called
   * immediately with null {@code cause} when this request is received.
   */
  ImmediateRequestComplete,
  /**
   * When this request is received, some data is initially written to the channel via
   * {@link RestResponseChannel#write(ByteBuffer)}. An attempt to modify response headers (metadata) is made after this.
   */
  ModifyResponseMetadataAfterWrite,
  /**
   * When this request is received, {@link RestResponseChannel#close()} is called multiple times.
   */
  MultipleClose,
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called multiple
   * times.
   */
  MultipleOnRequestComplete,
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called
   * immediately with a {@link RestServiceException} as {@code cause}. The exception message is the URI string and the
   * error code is {@link RestServiceErrorCode#BadRequest}.
   */
  OnRequestCompleteWithRseBr,
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called
   * immediately with a {@link RestServiceException} as {@code cause}. The exception message is the URI string and the
   * error code is {@link RestServiceErrorCode#InternalServerError}.
   */
  OnRequestCompleteWithRseIse,
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called
   * immediately with a {@link RestServiceException} as {@code cause}. The exception message is the URI string and the
   * error code is {@link RestServiceErrorCode#UnknownErrorCode}.
   */
  OnRequestCompleteWithRseUnknown,
  /**
   * When this request is received, {@link RestResponseChannel#onRequestComplete(Throwable, boolean)} is called
   * immediately with a {@link RuntimeException} as {@code cause}. The exception message is the URI string.
   */
  OnRequestCompleteWithRuntimeException,
  /**
   * When this request is received, the {@link RestResponseChannel} is closed and then a write operation is attempted.
   */
  WriteAfterClose,
  /**
   * When this request is received, a direct {@link ByteBuffer} is used as {@code src} for
   * {@link RestResponseChannel#write(ByteBuffer)}.
   */
  WriteWithDirectBuffer,
  /**
   * Catch all TestingUri.
   */
  Unknown;

  /**
   * Converts the uri specified by the input string into an {@link TestingUri}.
   * @param uri the TestingUri as a string.
   * @return the uri requested as a valid {@link TestingUri} if uri is known, otherwise returns {@link #Unknown}
   */
  public static TestingUri getTestingURI(String uri) {
    try {
      return TestingUri.valueOf(uri);
    } catch (IllegalArgumentException e) {
      return TestingUri.Unknown;
    }
  }
}

/**
 * A test handler that forms the pipeline of the {@link EmbeddedChannel} used in tests.
 * <p/>
 * Exposes some URI strings through which a predefined flow can be executed and verified.
 */
class MockNettyMessageProcessor extends SimpleChannelInboundHandler<HttpObject> {
  private RestRequestMetadata request;
  private RestResponseChannel restResponseChannel;

  @Override
  public void channelActive(ChannelHandlerContext ctx) {
    restResponseChannel = new NettyResponseChannel(ctx, new NettyMetrics(new MetricRegistry()));
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) {
    request = null;
    restResponseChannel = null;
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpObject obj)
      throws RestServiceException, IOException {
    if (obj != null && obj instanceof HttpRequest) {
      if (obj.getDecoderResult().isSuccess()) {
        handleRequest((HttpRequest) obj);
      } else {
        throw new RestServiceException("Malformed request received - " + obj, RestServiceErrorCode.MalformedRequest);
      }
    } else if (obj != null && obj instanceof HttpContent) {
      handleContent((HttpContent) obj);
    } else {
      throw new RestServiceException("HttpObject received is null or not of a known type",
          RestServiceErrorCode.UnknownHttpObject);
    }
  }

  /**
   * Handles a {@link HttpRequest}. If content is awaited, handles some state maintenance. Else handles the request
   * according to a predefined flow based on the uri.
   * @param httpRequest the {@link HttpRequest} that needs to be handled.
   * @throws RestServiceException
   */
  private void handleRequest(HttpRequest httpRequest)
      throws RestServiceException, IOException {
    if (request == null) {
      request = new NettyRequestMetadata(httpRequest);
      restResponseChannel.setContentType("text/plain; charset=UTF-8");
      TestingUri uri = TestingUri.getTestingURI(request.getUri());
      switch (uri) {
        case ImmediateRequestComplete:
          restResponseChannel.onRequestComplete(null, false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case ModifyResponseMetadataAfterWrite:
          restResponseChannel.write(ByteBuffer.wrap(new byte[0]));
          restResponseChannel.setContentType("text/plain; charset=UTF-8");
          break;
        case MultipleClose:
          restResponseChannel.onRequestComplete(null, false);
          restResponseChannel.close();
          restResponseChannel.close();
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case MultipleOnRequestComplete:
          restResponseChannel.onRequestComplete(null, false);
          restResponseChannel.onRequestComplete(null, false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case OnRequestCompleteWithRseBr:
          restResponseChannel.onRequestComplete(
              new RestServiceException(TestingUri.OnRequestCompleteWithRseBr.toString(),
                  RestServiceErrorCode.BadRequest), false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case OnRequestCompleteWithRseIse:
          restResponseChannel.onRequestComplete(
              new RestServiceException(TestingUri.OnRequestCompleteWithRseIse.toString(),
                  RestServiceErrorCode.InternalServerError), false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case OnRequestCompleteWithRseUnknown:
          restResponseChannel.onRequestComplete(
              new RestServiceException(TestingUri.OnRequestCompleteWithRseUnknown.toString(),
                  RestServiceErrorCode.UnknownErrorCode), false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case OnRequestCompleteWithRuntimeException:
          restResponseChannel
              .onRequestComplete(new RuntimeException(TestingUri.OnRequestCompleteWithRuntimeException.toString()),
                  false);
          assertTrue("Request not marked complete even after a call to onRequestComplete()",
              restResponseChannel.isRequestComplete());
          break;
        case WriteAfterClose:
          restResponseChannel.close();
          restResponseChannel.write(ByteBuffer.wrap(TestingUri.WriteAfterClose.toString().getBytes()));
          break;
        case WriteWithDirectBuffer:
          restResponseChannel.write(ByteBuffer.allocateDirect(1));
          break;
      }
    } else {
      restResponseChannel.onRequestComplete(null, false);
      assertTrue("Request not marked complete even after a call to onRequestComplete()",
          restResponseChannel.isRequestComplete());
    }
  }

  /**
   * Handles a {@link HttpContent}. Checks state and echoes back the content.
   * @param httpContent the {@link HttpContent} that needs to be handled.
   * @throws RestServiceException
   */
  private void handleContent(HttpContent httpContent)
      throws RestServiceException, IOException {
    if (request != null) {
      boolean isLast = httpContent instanceof LastHttpContent;
      restResponseChannel.write(ByteBuffer.wrap(httpContent.content().array()));
      if (isLast) {
        restResponseChannel.flush();
        restResponseChannel.onRequestComplete(null, false);
        assertTrue("Request not marked complete even after a call to onRequestComplete()",
            restResponseChannel.isRequestComplete());
      }
    } else {
      throw new RestServiceException("Received data without a request", RestServiceErrorCode.NoRequest);
    }
  }
}

/**
 * A {@link ChannelOutboundHandler} that throws exceptions on write.
 */
class BadOutboundHandler extends ChannelOutboundHandlerAdapter {
  protected static String EXCEPTION_MESSAGE = "@@randomExceptionMessage@@";

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    throw new Exception(EXCEPTION_MESSAGE);
  }
}
