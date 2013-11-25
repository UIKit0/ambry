package com.github.ambry.clustermap;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A Replica is one constituent piece of a {@link Partition}. A Replica is uniquely identifiable by its Partition and
 * its {@link Disk}. Note that this induces a constraint that a Partition can never have more than one Replica on a
 * given Disk. This ensures that a Partition does not have Replicas that share fates.
 */
public class Replica implements ReplicaId {
  private Partition partition;
  private Disk disk;

  private Logger logger = LoggerFactory.getLogger(getClass());

  public Replica(Partition partition, Disk disk) {
    this.partition = partition;
    this.disk = disk;

    validate();
  }

  public Replica(HardwareLayout hardwareLayout, Partition partition, JSONObject jsonObject) throws JSONException {
    this.partition = partition;

    this.disk = hardwareLayout.findDisk(jsonObject.getString("hostname"),
            jsonObject.getInt("port"), jsonObject.getString("mountPath"));

    validate();
  }

  @Override
  public PartitionId getPartitionId() {
    return getPartition();
  }

  @Override
  public DataNodeId getDataNodeId() {
    return disk.getDataNode();
  }

  @Override
  public String getMountPath() {
    return disk.getMountPath();
  }

  @Override
  public String getReplicaPath() {
    return getMountPath() + File.separator + partition.toPathString();
  }

  @Override
  public List<? extends ReplicaId> getPeerReplicaIds() {
    return getPeerReplicas();
  }

  public Partition getPartition() {
    return partition;
  }

  public Disk getDisk() {
    return disk;
  }

  public long getCapacityGB() {
    return partition.getReplicaCapacityGB();
  }

  public List<Replica> getPeerReplicas() {
    List<Replica> peers = new ArrayList<Replica>(partition.getReplicas().size());
    for (Replica peer : partition.getReplicas()) {
      if (!peer.equals(this)) {
        peers.add(peer);
      }
    }
    return peers;
  }

  protected void validatePartition() {
    if (partition == null) {
      throw new IllegalStateException("Partition cannot be null.");
    }
  }

  protected void validateDisk() {
    if (disk == null) {
      throw new IllegalStateException("Disk cannot be null.");
    }
  }

  protected void validate() {
    logger.trace("begin validate.");
    validatePartition();
    validateDisk();
    logger.trace("complete validate.");
  }

  public JSONObject toJSONObject() throws JSONException {
    // Effectively serializes the "foreign key" into hardwareLayout to find Disk.
    return new JSONObject()
            .put("hostname", disk.getDataNode().getHostname())
            .put("port", disk.getDataNode().getPort())
            .put("mountPath", disk.getMountPath());
  }

  @Override
  public String toString() {
    try {
      return toJSONObject().toString();
    } catch (JSONException e) {
      logger.error("JSONException caught in toString: {}",  e.getCause());
    }
    return null;
  }


}
