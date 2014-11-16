package com.github.ambry.clustermap;

import com.github.ambry.utils.SystemTime;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** ResourceStatePolicy is used to determine if the state of a resource is "up" or "down". For resources like data nodes
 *  and disks, up and down mean available and unavailable, respectively.
 */
public interface ResourceStatePolicy {
  /**
   * Checks to see if the state is permanently down.
   *
   * @return true if the state is permanently down, false otherwise.
   */
  public boolean isHardDown();

  /**
   * Checks to see if the state is down (soft or hard).
   *
   * @return true if the state is down, false otherwise.
   */
  public boolean isDown();

  /**
   * Should be called by the caller every time an error is encountered for the corresponding resource.
   *
   * @return true if we mark the resource as unavailable in this call.
   */
  public void onError();
}

abstract class FixedBackoffResourceStatePolicy implements ResourceStatePolicy {
  private final Object resource;
  private final boolean hardDown;
  private final int failureCountThreshold;
  private final long failureWindowSizeMs;
  private final long retryBackoffMs;
  private long downUntil;
  private AtomicBoolean down;
  private ArrayDeque<Long> failureQueue;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  public FixedBackoffResourceStatePolicy(Object resource, boolean hardDown, long failureWindowSizeMs,
      int failureCountThreshold, long retryBackoffMs) {
    this.resource = resource;
    this.hardDown = hardDown;
    this.failureWindowSizeMs = failureWindowSizeMs;
    this.failureCountThreshold = failureCountThreshold;
    this.retryBackoffMs = retryBackoffMs;
    this.downUntil = 0;
    this.down = new AtomicBoolean(false);
    failureQueue = new ArrayDeque<Long>();
  }

  /** On error, check if there have been threshold number of errors in the last failure window milliseconds.
   *  If so, make this resource down until now + retryBackoffMs. The size of the queue is bounded by the threshold.
   */
  @Override
  public void onError() {
    synchronized (this) {
      // Ignore errors if it has already been determined that the node is down.
      if (!down.get()) {
        while (failureQueue.size() > 0
            && failureQueue.getFirst() < SystemTime.getInstance().milliseconds() - failureWindowSizeMs) {
          failureQueue.remove();
        }
        if (failureQueue.size() < failureCountThreshold) {
          failureQueue.add(SystemTime.getInstance().milliseconds());
        } else {
          failureQueue.clear();
          down.set(true);
          downUntil = SystemTime.getInstance().milliseconds() + retryBackoffMs;
          logger.error("Resource " + resource + " has gone down");
        }
      }
    }
  }

  /* If down (which is checked locklessly), check to see if it is time to be up.
   */
  @Override
  public boolean isDown() {
    boolean ret = false;
    if (hardDown) {
      ret = true;
    } else if (down.get()) {
      synchronized (this) {
        if (SystemTime.getInstance().milliseconds() > downUntil) {
          down.set(false);
        } else {
          ret = true;
        }
      }
    }
    return ret;
  }

  @Override
  public boolean isHardDown() {
    return hardDown;
  }
}

class DataNodeStatePolicy extends FixedBackoffResourceStatePolicy {
  public DataNodeStatePolicy(DataNode node, HardwareState initialState, long failureWindowInitialSizeMs,
      int failureCountThreshold, long retryBackoffMs) {
    super(node, initialState == HardwareState.UNAVAILABLE, failureWindowInitialSizeMs, failureCountThreshold,
        retryBackoffMs);
  }

  public HardwareState getState() {
    return isDown() ? HardwareState.UNAVAILABLE : HardwareState.AVAILABLE;
  }
}

class DiskStatePolicy extends FixedBackoffResourceStatePolicy {

  public DiskStatePolicy(Disk disk, HardwareState initialState, long failureWindowInitialSizeMs,
      int failureCountThreshold, long retryBackoffMs) {
    super(disk, initialState == HardwareState.UNAVAILABLE, failureWindowInitialSizeMs, failureCountThreshold,
        retryBackoffMs);
  }

  public HardwareState getState() {
    return isDown() ? HardwareState.UNAVAILABLE : HardwareState.AVAILABLE;
  }
}
