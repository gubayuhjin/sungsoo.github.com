package org.apache.tez.runtime.library.input;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.TezRuntimeFrameworkConfigs;
import org.apache.tez.common.TezUtils;
import org.apache.tez.common.counters.TaskCounter;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.runtime.api.AbstractLogicalInput;
import org.apache.tez.runtime.api.Event;
import org.apache.tez.runtime.library.api.KeyValueReader;
import org.apache.tez.runtime.library.common.ConfigUtils;
import org.apache.tez.runtime.library.common.MemoryUpdateCallbackHandler;
import org.apache.tez.runtime.library.common.readers.ShuffledUnorderedKVReader;
import org.apache.tez.runtime.library.shuffle.common.ShuffleEventHandler;
import org.apache.tez.runtime.library.shuffle.common.impl.ShuffleInputEventHandlerImpl;
import org.apache.tez.runtime.library.shuffle.common.impl.ShuffleManager;
import org.apache.tez.runtime.library.shuffle.common.impl.SimpleFetchedInputAllocator;

import com.google.common.base.Preconditions;

public class ShuffledUnorderedKVInput extends AbstractLogicalInput {

  private static final Log LOG = LogFactory.getLog(ShuffledUnorderedKVInput.class);
  
  private Configuration conf;
  private ShuffleManager shuffleManager;
  private final BlockingQueue<Event> pendingEvents = new LinkedBlockingQueue<Event>();
  private long firstEventReceivedTime = -1;
  private MemoryUpdateCallbackHandler memoryUpdateCallbackHandler;
  @SuppressWarnings("rawtypes")
  private ShuffledUnorderedKVReader kvReader;
  
  private final AtomicBoolean isStarted = new AtomicBoolean(false);
  private TezCounter inputRecordCounter;
  
  private SimpleFetchedInputAllocator inputManager;
  private ShuffleEventHandler inputEventHandler;

  public ShuffledUnorderedKVInput() {
  }

  @Override
  public synchronized List<Event> initialize() throws Exception {
    Preconditions.checkArgument(getNumPhysicalInputs() != -1, "Number of Inputs has not been set");
    this.conf = TezUtils.createConfFromUserPayload(getContext().getUserPayload());

    if (getNumPhysicalInputs() == 0) {
      getContext().requestInitialMemory(0l, null);
      isStarted.set(true);
      getContext().inputIsReady();
      LOG.info("input fetch not required since there are 0 physical inputs for input vertex: "
          + getContext().getSourceVertexName());
      return Collections.emptyList();
    } else {
      long initalMemReq = getInitialMemoryReq();
      memoryUpdateCallbackHandler = new MemoryUpdateCallbackHandler();
      this.getContext().requestInitialMemory(initalMemReq, memoryUpdateCallbackHandler);
    }

    this.conf.setStrings(TezRuntimeFrameworkConfigs.LOCAL_DIRS, getContext().getWorkDirs());
    this.inputRecordCounter = getContext().getCounters().findCounter(
        TaskCounter.INPUT_RECORDS_PROCESSED);
    return Collections.emptyList();
  }

  @Override
  public synchronized void start() throws IOException {
    if (!isStarted.get()) {
      ////// Initial configuration
      memoryUpdateCallbackHandler.validateUpdateReceived();
      CompressionCodec codec;
      if (ConfigUtils.isIntermediateInputCompressed(conf)) {
        Class<? extends CompressionCodec> codecClass = ConfigUtils
            .getIntermediateInputCompressorClass(conf, DefaultCodec.class);
        codec = ReflectionUtils.newInstance(codecClass, conf);
      } else {
        codec = null;
      }

      boolean ifileReadAhead = conf.getBoolean(TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD,
          TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_DEFAULT);
      int ifileReadAheadLength = 0;
      int ifileBufferSize = 0;

      if (ifileReadAhead) {
        ifileReadAheadLength = conf.getInt(TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_BYTES,
            TezJobConfig.TEZ_RUNTIME_IFILE_READAHEAD_BYTES_DEFAULT);
      }
      ifileBufferSize = conf.getInt("io.file.buffer.size",
          TezJobConfig.TEZ_RUNTIME_IFILE_BUFFER_SIZE_DEFAULT);

      this.inputManager = new SimpleFetchedInputAllocator(getContext().getUniqueIdentifier(), conf,
          getContext().getTotalMemoryAvailableToTask(),
          memoryUpdateCallbackHandler.getMemoryAssigned());

      this.shuffleManager = new ShuffleManager(getContext(), conf, getNumPhysicalInputs(), ifileBufferSize,
          ifileReadAhead, ifileReadAheadLength, codec, inputManager);

      this.inputEventHandler = new ShuffleInputEventHandlerImpl(getContext(), shuffleManager,
          inputManager, codec, ifileReadAhead, ifileReadAheadLength);

      ////// End of Initial configuration

      this.shuffleManager.run();
      this.kvReader = createReader(inputRecordCounter, codec,
          ifileBufferSize, ifileReadAhead, ifileReadAheadLength);
      List<Event> pending = new LinkedList<Event>();
      pendingEvents.drainTo(pending);
      if (pending.size() > 0) {
        LOG.info("NoAutoStart delay in processing first event: "
            + (System.currentTimeMillis() - firstEventReceivedTime));
        inputEventHandler.handleEvents(pending);
      }
      isStarted.set(true);
    }
  }

  @Override
  public synchronized KeyValueReader getReader() throws Exception {
    Preconditions.checkState(isStarted.get(), "Must start input before invoking this method");
    if (getNumPhysicalInputs() == 0) {
      return new KeyValueReader() {
        @Override
        public boolean next() throws IOException {
          return false;
        }

        @Override
        public Object getCurrentKey() throws IOException {
          throw new RuntimeException("No data available in Input");
        }

        @Override
        public Object getCurrentValue() throws IOException {
          throw new RuntimeException("No data available in Input");
        }
      };
    }
    return this.kvReader;
  }

  @Override
  public void handleEvents(List<Event> inputEvents) throws IOException {
    synchronized (this) {
      if (getNumPhysicalInputs() == 0) {
        throw new RuntimeException("No input events expected as numInputs is 0");
      }
      if (!isStarted.get()) {
        if (firstEventReceivedTime == -1) {
          firstEventReceivedTime = System.currentTimeMillis();
        }
        // This queue will keep growing if the Processor decides never to
        // start the event. The Input, however has no idea, on whether start
        // will be invoked or not.
        pendingEvents.addAll(inputEvents);
        return;
      }
    }
    inputEventHandler.handleEvents(inputEvents);
  }

  @Override
  public synchronized List<Event> close() throws Exception {
    if (this.shuffleManager != null) {
      this.shuffleManager.shutdown();
    }
    return null;
  }

  private long getInitialMemoryReq() {
    return SimpleFetchedInputAllocator.getInitialMemoryReq(conf,
        getContext().getTotalMemoryAvailableToTask());
  }
  
  
  @SuppressWarnings("rawtypes")
  private ShuffledUnorderedKVReader createReader(TezCounter inputRecordCounter, CompressionCodec codec,
      int ifileBufferSize, boolean ifileReadAheadEnabled, int ifileReadAheadLength)
      throws IOException {
    return new ShuffledUnorderedKVReader(shuffleManager, conf, codec, ifileReadAheadEnabled,
        ifileReadAheadLength, ifileBufferSize, inputRecordCounter);
  }

}

