/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.container.common.states.endpoint;

import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdds.protocol.proto
    .StorageContainerDatanodeProtocolProtos.SCMVersionResponseProto;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.statemachine
    .EndpointStateMachine;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.VolumeSet;
import org.apache.hadoop.ozone.container.ozoneimpl.OzoneContainer;
import org.apache.hadoop.ozone.protocol.VersionResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Task that returns version.
 */
public class VersionEndpointTask implements
    Callable<EndpointStateMachine.EndPointStates> {
  private final EndpointStateMachine rpcEndPoint;
  private final Configuration configuration;
  private final OzoneContainer ozoneContainer;

  public VersionEndpointTask(EndpointStateMachine rpcEndPoint,
                             Configuration conf, OzoneContainer container) {
    this.rpcEndPoint = rpcEndPoint;
    this.configuration = conf;
    this.ozoneContainer = container;
  }

  /**
   * Computes a result, or throws an exception if unable to do so.
   *
   * @return computed result
   * @throws Exception if unable to compute a result
   */
  @Override
  public EndpointStateMachine.EndPointStates call() throws Exception {
    rpcEndPoint.lock();
    try{
      SCMVersionResponseProto versionResponse =
          rpcEndPoint.getEndPoint().getVersion(null);
      VersionResponse response = VersionResponse.getFromProtobuf(
          versionResponse);
      rpcEndPoint.setVersion(response);
      VolumeSet volumeSet = ozoneContainer.getVolumeSet();
      Map<String, HddsVolume> volumeMap = volumeSet.getVolumeMap();

      String scmId = response.getValue(OzoneConsts.SCM_ID);
      String clusterId = response.getValue(OzoneConsts.CLUSTER_ID);

      Preconditions.checkNotNull(scmId, "Reply from SCM: scmId cannot be " +
          "null");
      Preconditions.checkNotNull(scmId, "Reply from SCM: clusterId cannot be" +
          " null");

      // If version file does not exist create version file and also set scmId
      for (Map.Entry<String, HddsVolume> entry : volumeMap.entrySet()) {
        HddsVolume hddsVolume = entry.getValue();
        hddsVolume.format(clusterId);
        ozoneContainer.getDispatcher().setScmId(scmId);
      }

      EndpointStateMachine.EndPointStates nextState =
          rpcEndPoint.getState().getNextState();
      rpcEndPoint.setState(nextState);
      rpcEndPoint.zeroMissedCount();
    } catch (IOException ex) {
      rpcEndPoint.logIfNeeded(ex);
    } finally {
      rpcEndPoint.unlock();
    }
    return rpcEndPoint.getState();
  }
}
