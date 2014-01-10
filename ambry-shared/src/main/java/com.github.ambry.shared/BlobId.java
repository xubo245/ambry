package com.github.ambry.shared;

import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.store.StoreKey;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Utils;

import javax.xml.bind.DatatypeConverter;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * BlobId uniquely identifies a stored blob as well as the Partition in which the blob is stored.
 */
public class BlobId extends StoreKey {
  private Short version = 1;
  private static short Version_Size_In_Bytes = 2;
  private PartitionId partitionId;
  private String uuid;
  private static int UUID_Size_In_Bytes = 4;

  /**
   * Constructs a new unique BlobId for the specified partition.
   *
   * @param partitionId of Partition in which blob is to be stored.
   */
  public BlobId(PartitionId partitionId) {
    this.partitionId = partitionId;
    this.uuid = UUID.randomUUID().toString();
  }

  /**
   * Re-constructs existing blobId by deserializing from BlobId "string"
   *
   * @param id of Blob as output by BlobId.toString()
   * @param clusterMap
   * @throws IOException
   */
  public BlobId(String id, ClusterMap clusterMap) throws IOException {
    this(new DataInputStream(new ByteBufferInputStream(ByteBuffer.wrap(DatatypeConverter.parseHexBinary(id)))),
         clusterMap);
  }

  /**
   * Re-constructs existing blobId by deserializing from data input stream
   *
   * @param stream
   * @throws IOException
   */
  public BlobId(DataInputStream stream, ClusterMap clusterMap) throws IOException {
    this.version = stream.readShort();
    this.partitionId = clusterMap.getPartitionIdFromStream(stream);
    uuid = Utils.readIntString(stream);
  }

  public short sizeInBytes() {
    return (short)(Version_Size_In_Bytes + partitionId.getBytes().length + UUID_Size_In_Bytes + uuid.length());
  }

  public PartitionId getPartition() {
    return partitionId;
  }

  @Override
  public byte[] toBytes() {
    ByteBuffer idBuf = ByteBuffer.allocate(sizeInBytes());
    idBuf.putShort(version);
    idBuf.put(partitionId.getBytes());
    idBuf.putInt(uuid.getBytes().length);
    idBuf.put(uuid.getBytes());
    return idBuf.array();
  }

  @Override
  public String toString() {
    //return DatatypeConverter.printBase64Binary(toBytes());
    return DatatypeConverter.printHexBinary(toBytes());
  }

  @Override
  public int compareTo(StoreKey o) {
    BlobId other = (BlobId)o;

    int result = version.compareTo(other.version);
    if (result == 0) {
      result = partitionId.compareTo(other.partitionId);
      if (result == 0) {
        result = uuid.compareTo(other.uuid);
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    BlobId blobId = (BlobId)o;

    if (!version.equals(blobId.version)) return false;
    if (!partitionId.equals(blobId.partitionId)) return false;
    if (!uuid.equals(blobId.uuid)) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return Utils.hashcode(new Object[]{version, partitionId, uuid});
  }
}
