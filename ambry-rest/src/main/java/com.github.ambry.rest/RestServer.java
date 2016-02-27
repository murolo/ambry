package com.github.ambry.rest;

import com.codahale.metrics.JmxReporter;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.router.Router;
import com.github.ambry.router.RouterFactory;
import com.github.ambry.utils.Utils;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The RestServer represents any RESTful service (frontend, admin etc.) whose main concern is to receive requests from
 * clients through a REST protocol (HTTP), handle them appropriately by contacting the backend service if required and
 * return responses via the same REST protocol.
 * <p/>
 * The RestServer is responsible for starting up (and shutting down) multiple services required to handle requests from
 * clients. Currently it starts/shuts down the following: -
 * 1. A {@link Router} - A service that is used to contact the backend service.
 * 2. A {@link BlobStorageService} - A service that understands the operations supported by the backend service and can
 * handle requests from clients for such operations.
 * 3. A {@link NioServer} - To receive requests and return responses via a REST protocol (HTTP).
 * 4. A {@link RestRequestHandler} and a {@link RestResponseHandler} - Scaling units that are responsible for
 * interfacing between the {@link NioServer} and the {@link BlobStorageService}.
 * <p/>
 * Depending upon what is specified in the configuration file, the RestServer can start different implementations of
 * {@link NioServer} and {@link BlobStorageService} and behave accordingly.
 * <p/>
 * With RestServer, the goals are threefold:-
 * 1. To support ANY RESTful frontend service as long as it can provide an implementation of {@link BlobStorageService}.
 * 2. Make it easy to plug in any implementation of {@link NioServer} as long as it can provide implementations that
 * abstract framework specific objects and actions (like write/read from channel) into generic APIs through
 * {@link RestRequest}, {@link RestResponseChannel} etc.
 * 3. Provide scaling capabilities independent of any other component through {@link RestRequestHandler} and
 * {@link RestResponseHandler}.
 */
public class RestServer {
  private final CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final Logger logger = LoggerFactory.getLogger(getClass());
  private final RestServerMetrics restServerMetrics;
  private final JmxReporter reporter;
  private final Router router;
  private final BlobStorageService blobStorageService;
  private final RestRequestHandler restRequestHandler;
  private final RestResponseHandler restResponseHandler;
  private final NioServer nioServer;

  /**
   * Creates an instance of RestServer.
   * @param verifiableProperties the properties that define the behavior of the RestServer and its components.
   * @param clusterMap the {@link ClusterMap} instance that needs to be used.
   * @param notificationSystem the {@link NotificationSystem} instance that needs to be used.
   * @throws InstantiationException if there is any error instantiating an instance of RestServer.
   */
  public RestServer(VerifiableProperties verifiableProperties, ClusterMap clusterMap,
      NotificationSystem notificationSystem)
      throws InstantiationException {
    if (verifiableProperties == null || clusterMap == null || notificationSystem == null) {
      StringBuilder errorMessage = new StringBuilder("Null arg(s) received during instantiation of RestServer -");
      if (verifiableProperties == null) {
        errorMessage.append(" [VerifiableProperties] ");
      }
      if (clusterMap == null) {
        errorMessage.append(" [ClusterMap] ");
      }
      if (notificationSystem == null) {
        errorMessage.append(" [NotificationSystem] ");
      }
      throw new IllegalArgumentException(errorMessage.toString());
    }
    RestServerConfig restServerConfig = new RestServerConfig(verifiableProperties);
    reporter = JmxReporter.forRegistry(clusterMap.getMetricRegistry()).build();
    RestRequestMetricsTracker.setDefaults(clusterMap.getMetricRegistry());
    restServerMetrics = new RestServerMetrics(clusterMap.getMetricRegistry());
    try {
      RouterFactory routerFactory =
          Utils.getObj(restServerConfig.restServerRouterFactory, verifiableProperties, clusterMap, notificationSystem);
      router = routerFactory.getRouter();

      RestResponseHandlerFactory restResponseHandlerFactory = Utils
          .getObj(restServerConfig.restServerResponseHandlerFactory,
              restServerConfig.restServerResponseHandlerScalingUnitCount, restServerMetrics);
      restResponseHandler = restResponseHandlerFactory.getRestResponseHandler();

      BlobStorageServiceFactory blobStorageServiceFactory = Utils
          .getObj(restServerConfig.restServerBlobStorageServiceFactory, verifiableProperties, clusterMap,
              restResponseHandler, router);
      blobStorageService = blobStorageServiceFactory.getBlobStorageService();

      RestRequestHandlerFactory restRequestHandlerFactory = Utils
          .getObj(restServerConfig.restServerRequestHandlerFactory,
              restServerConfig.restServerRequestHandlerScalingUnitCount, restServerMetrics, blobStorageService);
      restRequestHandler = restRequestHandlerFactory.getRestRequestHandler();

      NioServerFactory nioServerFactory = Utils
          .getObj(restServerConfig.restServerNioServerFactory, verifiableProperties, clusterMap.getMetricRegistry(),
              restRequestHandler);
      nioServer = nioServerFactory.getNioServer();
      if (router == null || restResponseHandler == null || blobStorageService == null || restRequestHandler == null
          || nioServer == null) {
        throw new InstantiationException("Some of the server components were null");
      }
    } catch (Exception e) {
      restServerMetrics.restServerInstantiationError.inc();
      logger.error("Exception during instantiation of RestServer", e);
      throw new InstantiationException("Exception while creating RestServer components - " + e.getLocalizedMessage());
    }
    logger.trace("Instantiated RestServer");
  }

  /**
   * Starts up all the components required. Returns when startup is FULLY complete.
   * @throws InstantiationException if the RestServer is unable to start.
   */
  public void start()
      throws InstantiationException {
    logger.info("Starting RestServer");
    long startupBeginTime = System.currentTimeMillis();
    try {
      // ordering is important.
      reporter.start();
      long reporterStartTime = System.currentTimeMillis();
      long elapsedTime = reporterStartTime - startupBeginTime;
      logger.info("JMX reporter start took {} ms", elapsedTime);
      restServerMetrics.jmxReporterStartTimeInMs.update(elapsedTime);

      restResponseHandler.start();
      long restResponseHandlerStartTime = System.currentTimeMillis();
      elapsedTime = restResponseHandlerStartTime - reporterStartTime;
      logger.info("Response handler start took {} ms", elapsedTime);
      restServerMetrics.restResponseHandlerStartTimeInMs.update(elapsedTime);

      blobStorageService.start();
      long blobStorageServiceStartTime = System.currentTimeMillis();
      elapsedTime = blobStorageServiceStartTime - restResponseHandlerStartTime;
      logger.info("Blob storage service start took {} ms", elapsedTime);
      restServerMetrics.blobStorageServiceStartTimeInMs.update(elapsedTime);

      restRequestHandler.start();
      long restRequestHandlerStartTime = System.currentTimeMillis();
      elapsedTime = restRequestHandlerStartTime - blobStorageServiceStartTime;
      logger.info("Request handler start took {} ms", elapsedTime);
      restServerMetrics.restRequestHandlerStartTimeInMs.update(elapsedTime);

      nioServer.start();
      elapsedTime = System.currentTimeMillis() - restRequestHandlerStartTime;
      logger.info("NIO server start took {} ms", elapsedTime);
      restServerMetrics.nioServerStartTimeInMs.update(elapsedTime);
    } finally {
      long startupTime = System.currentTimeMillis() - startupBeginTime;
      logger.info("RestServer start took {} ms", startupTime);
      restServerMetrics.restServerStartTimeInMs.update(startupTime);
    }
  }

  /**
   * Shuts down all the components. Returns when shutdown is FULLY complete.
   */
  public void shutdown() {
    logger.info("Shutting down RestServer");
    long shutdownBeginTime = System.currentTimeMillis();
    try {
      //ordering is important.
      nioServer.shutdown();
      long nioServerShutdownTime = System.currentTimeMillis();
      long elapsedTime = nioServerShutdownTime - shutdownBeginTime;
      logger.info("NIO server shutdown took {} ms", elapsedTime);
      restServerMetrics.nioServerShutdownTimeInMs.update(elapsedTime);

      restRequestHandler.shutdown();
      long requestHandlerShutdownTime = System.currentTimeMillis();
      elapsedTime = requestHandlerShutdownTime - nioServerShutdownTime;
      logger.info("Request handler shutdown took {} ms", elapsedTime);
      restServerMetrics.restRequestHandlerShutdownTimeInMs.update(elapsedTime);

      blobStorageService.shutdown();
      long blobStorageServiceShutdownTime = System.currentTimeMillis();
      elapsedTime = blobStorageServiceShutdownTime - requestHandlerShutdownTime;
      logger.info("Blob storage service shutdown took {} ms", elapsedTime);
      restServerMetrics.blobStorageServiceShutdownTimeInMs.update(elapsedTime);

      restResponseHandler.shutdown();
      long responseHandlerShutdownTime = System.currentTimeMillis();
      elapsedTime = responseHandlerShutdownTime - blobStorageServiceShutdownTime;
      logger.info("Response handler shutdown took {} ms", elapsedTime);
      restServerMetrics.restResponseHandlerShutdownTimeInMs.update(elapsedTime);

      router.close();
      long routerCloseTime = System.currentTimeMillis();
      elapsedTime = routerCloseTime - responseHandlerShutdownTime;
      logger.info("Router close took {} ms", elapsedTime);
      restServerMetrics.routerCloseTime.update(elapsedTime);

      reporter.stop();
      elapsedTime = System.currentTimeMillis() - routerCloseTime;
      logger.info("JMX reporter shutdown took {} ms", elapsedTime);
      restServerMetrics.jmxReporterShutdownTimeInMs.update(elapsedTime);
    } catch (IOException e) {
      logger.error("Exception during shutdown", e);
    } finally {
      long shutdownTime = System.currentTimeMillis() - shutdownBeginTime;
      logger.info("RestServer shutdown took {} ms", shutdownTime);
      restServerMetrics.restServerShutdownTimeInMs.update(shutdownTime);
      shutdownLatch.countDown();
    }
  }

  /**
   * Wait for shutdown to be triggered and for it to complete.
   * @throws InterruptedException if the wait for shutdown is interrupted.
   */
  public void awaitShutdown()
      throws InterruptedException {
    shutdownLatch.await();
  }
}