/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extensions.vm.internal.listener;

import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.slf4j.LoggerFactory.getLogger;
import org.mule.extensions.vm.api.VMMessageAttributes;
import org.mule.extensions.vm.internal.QueueListenerDescriptor;
import org.mule.extensions.vm.internal.ReplyToCommand;
import org.mule.extensions.vm.internal.VMConnectorQueueManager;
import org.mule.extensions.vm.internal.connection.VMConnection;
import org.mule.runtime.api.component.location.ComponentLocation;
import org.mule.runtime.api.component.location.ConfigurationComponentLocator;
import org.mule.runtime.api.component.location.Location;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.api.metadata.TypedValue;
import org.mule.runtime.api.scheduler.Scheduler;
import org.mule.runtime.api.scheduler.SchedulerConfig;
import org.mule.runtime.api.scheduler.SchedulerService;
import org.mule.runtime.api.tx.TransactionException;
import org.mule.runtime.core.api.construct.Flow;
import org.mule.runtime.core.api.util.queue.Queue;
import org.mule.runtime.extension.api.annotation.Alias;
import org.mule.runtime.extension.api.annotation.execution.OnSuccess;
import org.mule.runtime.extension.api.annotation.execution.OnTerminate;
import org.mule.runtime.extension.api.annotation.param.Connection;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.source.EmitsResponse;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.source.Source;
import org.mule.runtime.extension.api.runtime.source.SourceCallback;
import org.mule.runtime.extension.api.runtime.source.SourceCallbackContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import org.slf4j.Logger;

/**
 * A source which creates and listens on a VM queues.
 * <p>
 * VM queues are created by placing listeners on them, which is why this listener contains parameters on the queue's
 * behaviour, such as it being persistent or not, the max capacity, etc.
 * <p>
 * The VM connector can only be used to publish and consume messages from queues for which a listener has been defined.
 *
 * @since 1.0
 */
@Alias("listener")
@EmitsResponse
public class VMListener extends Source<Serializable, VMMessageAttributes> {

  private static final Logger LOGGER = getLogger(VMListener.class);
  private static final String REPLY_TO_QUEUE_NAME = "replyTo";

  @Inject
  private VMConnectorQueueManager connectorQueueManager;

  @ParameterGroup(name = "queue")
  private QueueListenerDescriptor queueDescriptor;

  /**
   * The amount of concurrent consumers to be placed on the queue. As the number of consumers increases,
   * so does the speed on which this source pushes messages into the owning flow.
   */
  @Parameter
  @Optional(defaultValue = "4")
  private int numberOfConsumers;

  @Connection
  private ConnectionProvider<VMConnection> connectionProvider;

  @Inject
  private SchedulerService schedulerService;

  @Inject
  private ConfigurationComponentLocator componentLocator;

  private ComponentLocation location;

  private List<Consumer> consumers;
  private Scheduler scheduler;
  private Semaphore semaphore;

  @Override
  public void onStart(SourceCallback<Serializable, VMMessageAttributes> sourceCallback) throws MuleException {
    connectorQueueManager.createQueue(queueDescriptor, location.getRootContainerName());
    startConsumers(sourceCallback);
  }

  @Override
  public void onStop() {
    if (consumers != null) {
      consumers.forEach(Consumer::stop);
    }

    if (scheduler != null) {
      scheduler.stop();
    }
  }

  @OnSuccess
  public void onSuccess(@ParameterGroup(name = "Response", showInDsl = true) VMResponseBuilder messageBuilder,
                        SourceCallbackContext ctx) {

    ctx.<String>getVariable(REPLY_TO_QUEUE_NAME).ifPresent(replyTo -> {
      VMConnection connection = ctx.getConnection();
      Queue queue;
      try {
        queue = connection.getQueue(replyTo);
      } catch (Exception e) {
        LOGGER.warn(format("Found exception trying to obtain replyTo queue '%s'", replyTo), e);
        return;
      }

      if (queue != null) {
        try {
          queue.offer(messageBuilder.getContent(), queueDescriptor.getQueueTimeoutInMillis());
        } catch (Exception e) {
          LOGGER.warn(format("Found exception trying to send response to replyTo queue '%s'", replyTo), e);
        }
      } else {
        LOGGER.warn("Could not send response to replyTo queue '{}' because it does not exists", replyTo);
      }
    });
  }

  @OnTerminate
  public void onTerminate() {
    semaphore.release();
  }

  private void startConsumers(SourceCallback<Serializable, VMMessageAttributes> sourceCallback) {
    createScheduler();
    consumers = new ArrayList<>(numberOfConsumers);
    semaphore = new Semaphore(getMaxConcurrency(), false);
    for (int i = 0; i < numberOfConsumers; i++) {
      final Consumer consumer = new Consumer(sourceCallback);
      consumers.add(consumer);
      scheduler.schedule(consumer::start, 0, MILLISECONDS);
    }
  }

  private void createScheduler() {
    scheduler = schedulerService.customScheduler(SchedulerConfig.config()
        .withMaxConcurrentTasks(numberOfConsumers)
        .withName("vm listener on flow " + location.getRootContainerName())
        .withPrefix("vm-listener-flow-" + location.getRootContainerName())
        .withShutdownTimeout(queueDescriptor.getTimeout(),
                             queueDescriptor.getTimeoutUnit()));
  }

  private int getMaxConcurrency() {
    Flow flow = (Flow) componentLocator.find(Location.builder().globalName(location.getRootContainerName()).build()).get();
    return flow.getMaxConcurrency();
  }

  private class Consumer {

    private final SourceCallback<Serializable, VMMessageAttributes> sourceCallback;
    private final AtomicBoolean stop = new AtomicBoolean(false);

    public Consumer(SourceCallback<Serializable, VMMessageAttributes> sourceCallback) {
      this.sourceCallback = sourceCallback;
    }

    public void start() {
      final long timeout = queueDescriptor.getQueueTimeoutInMillis();

      while (isAlive()) {
        SourceCallbackContext ctx = sourceCallback.createContext();
        Serializable value;
        try {
          semaphore.acquire();
          final VMConnection connection = connect(ctx);
          final Queue queue = connection.getQueue(queueDescriptor.getQueueName());
          value = queue.poll(timeout);

          if (value == null) {
            cancel(ctx.getConnection());
            continue;
          }

          Result.Builder resultBuilder = Result.<Serializable, VMMessageAttributes>builder()
              .attributes(new VMMessageAttributes(queueDescriptor.getQueueName()));

          if (value instanceof ReplyToCommand) {
            ReplyToCommand replyTo = (ReplyToCommand) value;
            ctx.addVariable(REPLY_TO_QUEUE_NAME, replyTo.getReplyToQueueName());

            value = replyTo.getValue();
          }

          if (value instanceof TypedValue) {
            TypedValue typedValue = (TypedValue) value;
            resultBuilder.output(typedValue.getValue())
                .mediaType(typedValue.getDataType().getMediaType());
          } else {
            resultBuilder.output(value);
          }

          Result<Serializable, VMMessageAttributes> result = resultBuilder.build();

          if (isAlive()) {
            sourceCallback.handle(result, ctx);
          } else {
            cancel(ctx.getConnection());
          }
        } catch (InterruptedException e) {
          stop();
          cancel(ctx.getConnection());
          LOGGER.info("Consumer for vm:listener on flow '{}' was interrupted. No more consuming for thread '{}'",
                      location.getRootContainerName(),
                      currentThread().getName());
        } catch (Exception e) {
          cancel(ctx.getConnection());
          if (LOGGER.isErrorEnabled()) {
            LOGGER.error(format("Consumer for vm:listener on flow '%s' found unexpected exception. Consuming will continue '",
                                location.getRootContainerName()),
                         e);
          }
        }
      }
    }

    private void cancel(VMConnection connection) {
      try {
        connection.rollback();
      } catch (TransactionException e) {
        if (LOGGER.isWarnEnabled()) {
          LOGGER.warn("Failed to rollback transaction: " + e.getMessage(), e);
        }
      }
      connectionProvider.disconnect(connection);
    }

    private VMConnection connect(SourceCallbackContext ctx) throws ConnectionException, TransactionException {
      VMConnection connection = connectionProvider.connect();
      ctx.bindConnection(connection);
      return connection;
    }

    private boolean isAlive() {
      return !stop.get() && !currentThread().isInterrupted();
    }

    public void stop() {
      stop.set(true);
    }
  }
}
