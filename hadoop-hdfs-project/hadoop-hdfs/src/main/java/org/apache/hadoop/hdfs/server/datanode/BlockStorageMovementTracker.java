/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.datanode.StoragePolicySatisfyWorker.BlockMovementAttemptFinished;
import org.apache.hadoop.hdfs.server.datanode.StoragePolicySatisfyWorker.BlocksMovementsStatusHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to track the completion of block movement future tasks.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class BlockStorageMovementTracker implements Runnable {
  private static final Logger LOG = LoggerFactory
      .getLogger(BlockStorageMovementTracker.class);
  private final CompletionService<BlockMovementAttemptFinished> moverCompletionService;
  private final BlocksMovementsStatusHandler blksMovementsStatusHandler;

  // Keeps the information - block vs its list of future move tasks
  private final Map<Block, List<Future<BlockMovementAttemptFinished>>> moverTaskFutures;
  private final Map<Block, List<BlockMovementAttemptFinished>> movementResults;

  private volatile boolean running = true;

  /**
   * BlockStorageMovementTracker constructor.
   *
   * @param moverCompletionService
   *          completion service.
   * @param handler
   *          blocks movements status handler
   */
  public BlockStorageMovementTracker(
      CompletionService<BlockMovementAttemptFinished> moverCompletionService,
      BlocksMovementsStatusHandler handler) {
    this.moverCompletionService = moverCompletionService;
    this.moverTaskFutures = new HashMap<>();
    this.blksMovementsStatusHandler = handler;
    this.movementResults = new HashMap<>();
  }

  @Override
  public void run() {
    while (running) {
      if (moverTaskFutures.size() <= 0) {
        try {
          synchronized (moverTaskFutures) {
            // Waiting for mover tasks.
            moverTaskFutures.wait(2000);
          }
        } catch (InterruptedException ignore) {
          // Sets interrupt flag of this thread.
          Thread.currentThread().interrupt();
        }
      }
      try {
        Future<BlockMovementAttemptFinished> future =
            moverCompletionService.take();
        if (future != null) {
          BlockMovementAttemptFinished result = future.get();
          LOG.debug("Completed block movement. {}", result);
          Block block = result.getBlock();
          List<Future<BlockMovementAttemptFinished>> blocksMoving =
              moverTaskFutures.get(block);
          if (blocksMoving == null) {
            LOG.warn("Future task doesn't exist for block : {} ", block);
            continue;
          }
          blocksMoving.remove(future);

          List<BlockMovementAttemptFinished> resultPerTrackIdList =
              addMovementResultToBlockIdList(result);

          // Completed all the scheduled blocks movement under this 'trackId'.
          if (blocksMoving.isEmpty() || moverTaskFutures.get(block) == null) {
            synchronized (moverTaskFutures) {
              moverTaskFutures.remove(block);
            }
            if (running) {
              // handle completed or inprogress blocks movements per trackId.
              blksMovementsStatusHandler.handle(resultPerTrackIdList);
            }
            movementResults.remove(block);
          }
        }
      } catch (InterruptedException e) {
        if (running) {
          LOG.error("Exception while moving block replica to target storage"
              + " type", e);
        }
      } catch (ExecutionException e) {
        // TODO: Do we need failure retries and implement the same if required.
        LOG.error("Exception while moving block replica to target storage type",
            e);
      }
    }
  }

  private List<BlockMovementAttemptFinished> addMovementResultToBlockIdList(
      BlockMovementAttemptFinished result) {
    Block block = result.getBlock();
    List<BlockMovementAttemptFinished> perBlockIdList;
    synchronized (movementResults) {
      perBlockIdList = movementResults.get(block);
      if (perBlockIdList == null) {
        perBlockIdList = new ArrayList<>();
        movementResults.put(block, perBlockIdList);
      }
      perBlockIdList.add(result);
    }
    return perBlockIdList;
  }

  /**
   * Add future task to the tracking list to check the completion status of the
   * block movement.
   *
   * @param blockID
   *          block identifier
   * @param futureTask
   *          future task used for moving the respective block
   */
  void addBlock(Block block,
      Future<BlockMovementAttemptFinished> futureTask) {
    synchronized (moverTaskFutures) {
      List<Future<BlockMovementAttemptFinished>> futures =
          moverTaskFutures.get(block);
      // null for the first task
      if (futures == null) {
        futures = new ArrayList<>();
        moverTaskFutures.put(block, futures);
      }
      futures.add(futureTask);
      // Notify waiting tracker thread about the newly added tasks.
      moverTaskFutures.notify();
    }
  }

  /**
   * Clear the pending movement and movement result queues.
   */
  void removeAll() {
    synchronized (moverTaskFutures) {
      moverTaskFutures.clear();
    }
    synchronized (movementResults) {
      movementResults.clear();
    }
  }

  /**
   * Sets running flag to false and clear the pending movement result queues.
   */
  public void stopTracking() {
    running = false;
    removeAll();
  }
}
