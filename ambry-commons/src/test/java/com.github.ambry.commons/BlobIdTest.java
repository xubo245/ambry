/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.commons;

import com.github.ambry.account.Account;
import com.github.ambry.account.Container;
import com.github.ambry.clustermap.ClusterMap;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.clustermap.MockPartitionId;
import com.github.ambry.clustermap.PartitionId;
import com.github.ambry.utils.ByteBufferInputStream;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.TestUtils;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.apache.commons.codec.binary.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static com.github.ambry.clustermap.ClusterMapUtils.*;
import static com.github.ambry.commons.BlobId.*;
import static com.github.ambry.utils.Utils.*;
import static org.junit.Assert.*;


/**
 * Unit tests for {@link BlobId}.
 */
@RunWith(Parameterized.class)
public class BlobIdTest {
  private static final Random random = new Random();
  private final short version;
  private final BlobIdType referenceType;
  private final byte referenceDatacenterId;
  private final short referenceAccountId;
  private final short referenceContainerId;
  private final ClusterMap referenceClusterMap;
  private final PartitionId referencePartitionId;
  private final boolean referenceIsEncrypted;
  private final BlobDataType referenceDataType;

  /**
   * Running for both {@link BlobId#BLOB_ID_V1} and {@link BlobId#BLOB_ID_V2}
   * @return an array with both {@link BlobId#BLOB_ID_V1} and {@link BlobId#BLOB_ID_V2}
   */
  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(new Object[][]{{BLOB_ID_V1}, {BLOB_ID_V2}, {BLOB_ID_V3}, {BLOB_ID_V4}, {BLOB_ID_V5}});
  }

  /**
   * Constructor with parameter to be set.
   * @param version The version for BlobId to test.
   */
  public BlobIdTest(short version) throws Exception {
    this.version = version;
    byte[] bytes = new byte[2];
    referenceClusterMap = new MockClusterMap();
    random.nextBytes(bytes);
    referenceType = random.nextBoolean() ? BlobIdType.NATIVE : BlobIdType.CRAFTED;
    random.nextBytes(bytes);
    referenceDatacenterId = bytes[0];
    referenceAccountId = getRandomShort(random);
    referenceContainerId = getRandomShort(random);
    referencePartitionId = referenceClusterMap.getWritablePartitionIds(MockClusterMap.DEFAULT_PARTITION_CLASS).get(0);
    referenceIsEncrypted = random.nextBoolean();
    referenceDataType = BlobDataType.values()[random.nextInt(BlobDataType.values().length)];
  }

  /**
   * Tests blobId construction and assert that values are as expected.
   */
  @Test
  public void testBuildBlobId() throws Exception {
    BlobId blobId = new BlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
        referencePartitionId, referenceIsEncrypted, referenceDataType);
    assertEquals("Wrong blobId version", version, getVersionFromBlobString(blobId.getID()));
    assertBlobIdFieldValues(version, blobId, referenceType, referenceDatacenterId, referenceAccountId,
        referenceContainerId, referencePartitionId, referenceIsEncrypted, referenceDataType);
  }

  /**
   * Tests first serializing a blobId into string, and then deserializing into a blobId object from the string.
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void testSerDes() throws Exception {
    BlobId blobId = new BlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
        referencePartitionId, referenceIsEncrypted, referenceDataType);
    deserializeBlobIdAndAssert(version, blobId.getID());
    BlobId blobIdSerDed =
        new BlobId(new DataInputStream(new ByteArrayInputStream(blobId.toBytes())), referenceClusterMap);
    // Ensure that deserialized blob is exactly the same as the original in comparisons.
    assertTrue(blobId.equals(blobIdSerDed));
    assertTrue(blobIdSerDed.equals(blobId));
    assertEquals(blobId.hashCode(), blobIdSerDed.hashCode());
    assertEquals(0, blobId.compareTo(blobIdSerDed));
    assertEquals(0, blobIdSerDed.compareTo(blobId));
  }

  /**
   * Test that the BlobId flag is honored by V3 and above.
   * @throws Exception
   */
  @Test
  public void testBlobIdFlag() throws Exception {
    boolean[] isEncryptedValues = {true, false};
    if (version >= BLOB_ID_V3) {
      for (BlobIdType type : BlobIdType.values()) {
        for (boolean isEncrypted : isEncryptedValues) {
          BlobId blobId = new BlobId(version, type, referenceDatacenterId, referenceAccountId, referenceContainerId,
              referencePartitionId, isEncrypted, referenceDataType);
          BlobId blobIdSerDed =
              new BlobId(new DataInputStream(new ByteArrayInputStream(blobId.toBytes())), referenceClusterMap);
          assertEquals("The type should match the original's type", type, blobIdSerDed.getType());
          assertEquals("The isEncrypted should match the original", version == BLOB_ID_V3 ? false : isEncrypted,
              BlobId.isEncrypted(blobId.getID()));
        }
      }
    }

    if (version >= BLOB_ID_V5) {
      for (BlobDataType dataType : BlobDataType.values()) {
        BlobId blobId =
            new BlobId(version, BlobIdType.NATIVE, referenceDatacenterId, referenceAccountId, referenceContainerId,
                referencePartitionId, false, dataType);
        BlobId blobIdSerDed =
            new BlobId(new DataInputStream(new ByteArrayInputStream(blobId.toBytes())), referenceClusterMap);
        assertEquals("The data type should match the original's", dataType, blobIdSerDed.getBlobDataType());
      }
    }
  }

  /**
   * Test {@link BlobId#isEncrypted(String)}.
   * BLOB_ID_V1 and BLOB_ID_V2 encrypted bit should always be {@code false);
   * BLOB_ID_V3 encrypted bit can be {@code true}} if BlobIdString has encrypted bit;
   * BLOB_ID_V4 encrypted bit is based on {@link BlobId} only.
   * @throws Exception
   */
  @Test
  public void testBlobIdIsEncrypted() throws Exception {
    BlobId blobIdV1 = getRandomBlobId(BLOB_ID_V1);
    BlobId blobIdV2 = getRandomBlobId(BLOB_ID_V2);
    BlobId blobIdV3 = getRandomBlobId(BLOB_ID_V3);
    // V1 and V2 should always return false
    assertFalse("V1 encrypted bit should be false", BlobId.isEncrypted(blobIdV1.getID()));
    assertFalse("V2 encrypted bit should be false", BlobId.isEncrypted(blobIdV2.getID()));
    // V3 return False if encrypted is not set in the string of blobId.
    assertFalse("V3 encrypted bit should be false", BlobId.isEncrypted(blobIdV3.getID()));

    // V3 should return true if blobIdString has encrypted bit
    assertTrue("V3 should return true if blobIdString has encrypted bit", BlobId.isEncrypted("AAME"));
    assertTrue("V3 should return true if blobIdString has encrypted bit", BlobId.isEncrypted("AAMF"));

    // V3 should return false if blobIdString has no encrypted
    assertFalse("V3 should return false if blobIdString has no encrypted", BlobId.isEncrypted("AAMA"));
    assertFalse("V3 should return false if blobIdString has no encrypted", BlobId.isEncrypted("AAMB"));

    // V4+ should return true or false based on its encrypted bit
    for (short version : new short[]{BLOB_ID_V4, BLOB_ID_V5}) {
      for (boolean isEncrypted : new boolean[]{true, false}) {
        BlobId blobId =
            new BlobId(version, random.nextBoolean() ? BlobIdType.NATIVE : BlobIdType.CRAFTED, (byte) 1, (short) 1,
                (short) 1, referenceClusterMap.getWritablePartitionIds(MockClusterMap.DEFAULT_PARTITION_CLASS)
                .get(random.nextInt(3)), isEncrypted, BlobDataType.DATACHUNK);
        assertEquals("V" + version + " should return true or false based on its encrypted bit", isEncrypted,
            BlobId.isEncrypted(blobId.getID()));
      }
    }
  }

  /**
   * Test various invalid blobIds
   * @throws Exception Any unexpected exception.
   */
  @Test
  public void badIdTest() throws Exception {
    generateAndAssertBadBlobId(version);
  }

  /**
   * Tests blobIds comparisons. Among other things, ensures the following requirements are met:
   * <br>
   * V1s are always lesser than V2s and V3s.
   * V2s are always lesser than V3s.
   */
  @Test
  public void testComparisons() throws Exception {
    // the version check is to do this inter-version test just once (since this is a parametrized test).
    if (version == BLOB_ID_V1) {
      for (int i = 0; i < 100; i++) {
        BlobId blobIdV1 = getRandomBlobId(BLOB_ID_V1);
        BlobId blobIdV2 = getRandomBlobId(BLOB_ID_V2);
        BlobId blobIdV3 = getRandomBlobId(BLOB_ID_V3);
        BlobId blobIdV4 = getRandomBlobId(BLOB_ID_V4);
        BlobId blobIdV5 = getRandomBlobId(BLOB_ID_V5);

        assertTrue("blobIdV1 should be less than blobIdV2", blobIdV1.compareTo(blobIdV2) < 0);
        assertFalse(blobIdV1.equals(blobIdV2));
        assertTrue("blobIdV1 should be less than blobIdV3", blobIdV1.compareTo(blobIdV3) < 0);
        assertFalse(blobIdV1.equals(blobIdV3));
        assertTrue("blobIdV2 should be less than blobIdV3", blobIdV2.compareTo(blobIdV3) < 0);
        assertFalse(blobIdV2.equals(blobIdV3));

        assertTrue("blobIdV3 should be greater than blobIdV2", blobIdV3.compareTo(blobIdV2) > 0);
        assertFalse(blobIdV3.equals(blobIdV2));
        assertTrue("blobIdV3 should be greater than blobIdV1", blobIdV3.compareTo(blobIdV1) > 0);
        assertFalse(blobIdV3.equals(blobIdV1));
        assertTrue("blobIdV2 should be greater than blobIdV1", blobIdV2.compareTo(blobIdV1) > 0);
        assertFalse(blobIdV2.equals(blobIdV1));

        assertTrue("blobIdV1 should be equal to blobIdV1", blobIdV1.compareTo(blobIdV1) == 0);
        assertTrue(blobIdV1.equals(blobIdV1));
        assertTrue("blobIdV2 should be equal to blobIdV2", blobIdV2.compareTo(blobIdV2) == 0);
        assertTrue(blobIdV2.equals(blobIdV2));
        assertTrue("blobIdV3 should be equal to blobIdV3", blobIdV3.compareTo(blobIdV3) == 0);
        assertTrue(blobIdV3.equals(blobIdV3));
        assertTrue("blobIdV4 should be equal to blobIdV4", blobIdV4.compareTo(blobIdV4) == 0);
        assertTrue(blobIdV4.equals(blobIdV4));
        assertTrue("blobIdV5 should be equal to blobIdV5", blobIdV5.compareTo(blobIdV5) == 0);
        assertTrue(blobIdV5.equals(blobIdV5));

        assertTrue("Comparison for blobIdV3 and above are based on uuid only",
            blobIdV3.compareTo(blobIdV4) == blobIdV3.getUuid().compareTo(blobIdV4.getUuid()));
        assertTrue("blobIdV3 and blobIdV4 should be unequal",
            blobIdV3.compareTo(blobIdV4) != blobIdV4.compareTo(blobIdV3));
        assertFalse("blobIdV3 and blobIdV4 should be unequal", blobIdV3.equals(blobIdV4));
        assertFalse("blobIdV3 and blobIdV4 should be unequal", blobIdV4.equals(blobIdV3));
        // V4 should greater than V1 and V2
        BlobId[] lowerVersions = {blobIdV1, blobIdV2};
        for (BlobId lowerVersion : lowerVersions) {
          assertTrue("blobIdV1 or blobIdV2 should be less than blobIdV4", lowerVersion.compareTo(blobIdV4) < 0);
          assertFalse(lowerVersion.equals(blobIdV4));
          assertTrue("blobIdV4 should be greater than blobIdV1 or blobIdV2", blobIdV4.compareTo(lowerVersion) > 0);
          assertFalse(blobIdV4.equals(lowerVersion));
        }

        BlobId blobIdV1Alt = getRandomBlobId(BLOB_ID_V1);
        BlobId blobIdV2Alt = getRandomBlobId(BLOB_ID_V2);
        BlobId blobIdV3Alt = getRandomBlobId(BLOB_ID_V3);
        BlobId blobIdV4Alt = getRandomBlobId(BLOB_ID_V4);
        BlobId blobIdV5Alt = getRandomBlobId(BLOB_ID_V5);

        assertFalse("Two randomly generated V1 blob ids should be unequal", blobIdV1.compareTo(blobIdV1Alt) == 0);
        assertFalse("Two randomly generated V1 blob ids should be unequal", blobIdV1.equals(blobIdV1Alt));

        assertFalse("Two randomly generated V2 blob ids should be unequal", blobIdV2.compareTo(blobIdV2Alt) == 0);
        assertFalse("Two randomly generated V2 blob ids should be unequal", blobIdV2.equals(blobIdV2Alt));

        assertFalse("Two randomly generated V3 blob ids should be unequal", blobIdV3.compareTo(blobIdV3Alt) == 0);
        assertFalse("Two randomly generated V3 blob ids should be unequal", blobIdV3.equals(blobIdV3Alt));

        assertFalse("Two randomly generated V4 blob ids should be unequal", blobIdV4.compareTo(blobIdV4Alt) == 0);
        assertFalse("Two randomly generated V4 blob ids should be unequal", blobIdV4.equals(blobIdV4Alt));

        assertFalse("Two randomly generated V5 blob ids should be unequal", blobIdV5.compareTo(blobIdV5Alt) == 0);
        assertFalse("Two randomly generated V5 blob ids should be unequal", blobIdV5.equals(blobIdV5Alt));
      }
    }
  }

  /**
   * Test crafting of BlobIds.
   * Ensure that, except for the version, type, account and container, crafted id has the same constituents as the
   * input id.
   * Ensure that crafted id is the same as the input id if the input is a crafted V3 id with the same account and
   * container as the account and container in the call to craft.
   */
  @Test
  public void testCrafting() throws Exception {
    BlobId inputs[];
    if (version >= BLOB_ID_V3) {
      inputs = new BlobId[]{
          new BlobId(version, BlobIdType.NATIVE, referenceDatacenterId, referenceAccountId, referenceContainerId,
              referencePartitionId, false, referenceDataType),
          new BlobId(version, BlobIdType.CRAFTED, referenceDatacenterId, referenceAccountId, referenceContainerId,
              referencePartitionId, false, referenceDataType)};
      assertFalse("isCrafted() should be false for native id", BlobId.isCrafted(inputs[0].getID()));
      assertTrue("isCrafted() should be true for crafted id", BlobId.isCrafted(inputs[1].getID()));
    } else {
      inputs = new BlobId[]{
          new BlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
              referencePartitionId, false, null)};
      assertFalse("isCrafted() should be false for ids below BLOB_ID_V3", BlobId.isCrafted(inputs[0].getID()));
    }
    short newAccountId = (short) (referenceAccountId + 1 + TestUtils.RANDOM.nextInt(100));
    short newContainerId = (short) (referenceContainerId + 1 + TestUtils.RANDOM.nextInt(100));

    BlobId crafted = null;
    for (BlobId id : inputs) {
      try {
        BlobId.craft(id, BLOB_ID_V1, newAccountId, newContainerId);
        fail("Crafting should fail for target version " + BLOB_ID_V1);
      } catch (IllegalArgumentException e) {
      }
      try {
        BlobId.craft(id, BLOB_ID_V2, newAccountId, newContainerId);
        fail("Crafting should fail for target version " + BLOB_ID_V2);
      } catch (IllegalArgumentException e) {
      }
      short idVersion = (short) Math.max(id.getVersion(), BLOB_ID_V3);
      crafted = BlobId.craft(id, idVersion, newAccountId, newContainerId);
      verifyCrafting(id, crafted);
    }

    BlobId craftedAgain = BlobId.craft(crafted, crafted.getVersion(), crafted.getAccountId(), crafted.getContainerId());
    verifyCrafting(crafted, craftedAgain);
    assertEquals("Accounts should match", crafted.getAccountId(), craftedAgain.getAccountId());
    assertEquals("Containers should match", crafted.getContainerId(), craftedAgain.getContainerId());
    assertEquals("The id string should match", crafted.getID(), craftedAgain.getID());

    if (version == BLOB_ID_V3) {
      // version check to avoid testing this repetitively.
      try {
        BlobId.isCrafted("");
        fail("Empty blob id should not get parsed");
      } catch (IOException e) {
      }
      try {
        BlobId.isCrafted("ZZZZZ");
        fail("Invalid version should get caught");
      } catch (IllegalArgumentException e) {
      }
    }
  }

  /**
   * Test for {@link BlobId#isAccountContainerMatch}.
   * For BLOB_ID_V1, {@link BlobId#isAccountContainerMatch} should always return true.
   * For BLOB_ID_V2 and BLOB_ID_V3, return true only when both account and container match.
   */
  @Test
  public void testIsAccountContainerMatch() throws Exception {
    BlobId blobIdV1 = getRandomBlobId(BLOB_ID_V1);
    BlobId blobIdV2 = getRandomBlobId(BLOB_ID_V2);
    BlobId blobIdV3 = getRandomBlobId(BLOB_ID_V3);
    BlobId blobIdV4 = getRandomBlobId(BLOB_ID_V4);
    BlobId blobIdV5 = getRandomBlobId(BLOB_ID_V5);
    // test v1
    assertTrue("isAccountContainerMatch() should always return true for  V1 blobID.",
        blobIdV1.isAccountContainerMatch(blobIdV1.getAccountId(), blobIdV1.getContainerId()));
    assertTrue("isAccountContainerMatch() should always return true for  V1 blobID.",
        blobIdV1.isAccountContainerMatch((short) -1, (short) -1));
    assertTrue("isAccountContainerMatch() should always return true for  V1 blobID.",
        blobIdV1.isAccountContainerMatch(getRandomShort(random), getRandomShort(random)));
    // test v2, v3 and v4
    BlobId[] blobIds = {blobIdV2, blobIdV3, blobIdV4, blobIdV5};
    for (BlobId blobId : blobIds) {
      assertTrue("isAccountContainerMatch() should return true because account and container match.",
          blobId.isAccountContainerMatch(blobId.getAccountId(), blobId.getContainerId()));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch(blobId.getAccountId(), (short) -1));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch(blobId.getAccountId(), getRandomShort(random)));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch((short) -1, blobId.getContainerId()));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch(getRandomShort(random), blobId.getContainerId()));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch((short) -1, (short) -1));
      assertFalse("isAccountContainerMatch() should return false because account or container mismatch.",
          blobId.isAccountContainerMatch(getRandomShort(random), getRandomShort(random)));
    }
  }

  /**
   * Assert that the given crafted ids constituent fields except for version, type, account, container, match those of
   * the given input id.
   * Also assert the version and type of the crafted id.
   * @param input the input BlobId with the expected fields for comparison.
   * @param crafted the crafted BlobId whose fields must match the arguments of the other BlobId.
   */
  private void verifyCrafting(BlobId input, BlobId crafted) throws IOException {
    assertEquals("Datacenter id of input id should match that of the crafted id", input.getDatacenterId(),
        crafted.getDatacenterId());
    assertEquals("Partition of input id should match that of the crafted id", input.getPartition(),
        crafted.getPartition());
    assertEquals("UUID of input id should match that of the crafted id", input.getUuid(), crafted.getUuid());
    assertTrue("Crafted id should have at least version 3", crafted.getVersion() >= BLOB_ID_V3);
    assertEquals("Crafted id should have the Crafted type", BlobIdType.CRAFTED, crafted.getType());
    assertTrue("isCrafted() should be true for crafted ids", BlobId.isCrafted(crafted.getID()));
  }

  /**
   * Generates bad blobId strings, and deserializes from the string.
   * @param version The version of BlobId.
   * @throws Exception Any unexpected exception.
   */
  private void generateAndAssertBadBlobId(Short version) throws Exception {
    List<String> invalidBlobIdLikeList = new ArrayList<>();
    PartitionId badPartitionId =
        new MockPartitionId(200000, MockClusterMap.DEFAULT_PARTITION_CLASS, Collections.EMPTY_LIST, 0);
    String goodUUID = UUID.randomUUID().toString();

    // Partition ID not in cluster map
    invalidBlobIdLikeList.add(
        buildBadBlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            badPartitionId, goodUUID.length(), goodUUID));
    // UUID length too long
    invalidBlobIdLikeList.add(
        buildBadBlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            referencePartitionId, goodUUID.length() + 1, goodUUID));
    // UUID length too short
    invalidBlobIdLikeList.add(
        buildBadBlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            referencePartitionId, goodUUID.length() - 1, goodUUID));
    // UUID length is negative
    invalidBlobIdLikeList.add(
        buildBadBlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            referencePartitionId, -1, goodUUID));
    // Extra characters after UUID
    invalidBlobIdLikeList.add(
        buildBadBlobId(version, referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            referencePartitionId, goodUUID.length(), goodUUID + "EXTRA"));
    // Invalid version number
    invalidBlobIdLikeList.add(
        buildBadBlobId((short) (-1), referenceType, referenceDatacenterId, referenceAccountId, referenceContainerId,
            referencePartitionId, goodUUID.length(), goodUUID));
    // Empty blobId
    invalidBlobIdLikeList.add("");
    // short Blob ID
    invalidBlobIdLikeList.add("AA");

    for (String blobIdLike : invalidBlobIdLikeList) {
      try {
        new BlobId(blobIdLike, referenceClusterMap);
        fail("Expected blobId creation to fail with blobId string " + blobIdLike);
      } catch (Exception e) {
        // expected
      }
    }
  }

  /**
   * Build a string that resembles a bad blobId.
   * @param version The version number to be embedded in the blobId.
   * @param type The {@link BlobIdType} of the blobId.
   * @param datacenterId The datacenter id to be embedded in the blobId.
   * @param accountId The account id to be embedded in the blobId.
   * @param containerId The container id to be embedded in the blobId.
   * @param partitionId The partition id to be embedded in the blobId.
   * @param uuidLength The length of the uuid.
   * @param uuidLike The UUID to be embedded in the blobId.
   * @return a base-64 encoded {@link String} representing the blobId.
   */
  private String buildBadBlobId(short version, BlobIdType type, Byte datacenterId, Short accountId, Short containerId,
      PartitionId partitionId, int uuidLength, String uuidLike) {
    int idLength;
    ByteBuffer idBuf;
    switch (version) {
      case BLOB_ID_V1:
        idLength = 2 + partitionId.getBytes().length + 4 + uuidLike.length();
        idBuf = ByteBuffer.allocate(idLength);
        idBuf.putShort(version);
        break;
      case BLOB_ID_V2:
        idLength = 2 + 1 + 1 + 2 + 2 + partitionId.getBytes().length + 4 + uuidLike.length();
        idBuf = ByteBuffer.allocate(idLength);
        idBuf.putShort(version);
        idBuf.put((byte) 0);
        idBuf.put(datacenterId);
        idBuf.putShort(accountId);
        idBuf.putShort(containerId);
        break;
      case BLOB_ID_V3:
      case BLOB_ID_V4:
      case BLOB_ID_V5:
        idLength = 2 + 1 + 1 + 2 + 2 + partitionId.getBytes().length + 4 + uuidLike.length();
        idBuf = ByteBuffer.allocate(idLength);
        idBuf.putShort(version);
        idBuf.put((byte) type.ordinal());
        idBuf.put(datacenterId);
        idBuf.putShort(accountId);
        idBuf.putShort(containerId);
        break;
      default:
        idLength = 2 + partitionId.getBytes().length + 4 + uuidLike.length();
        idBuf = ByteBuffer.allocate(idLength);
        idBuf.putShort(version);
        break;
    }
    idBuf.put(partitionId.getBytes());
    idBuf.putInt(uuidLength);
    idBuf.put(uuidLike.getBytes());
    return Base64.encodeBase64URLSafeString(idBuf.array());
  }

  /**
   * Deserializes BlobId string and assert the resulted BlobId object.
   * @param version The version of BlobId.
   * @param srcBlobIdStr The string to deserialize.
   * @throws Exception Any unexpected exception.
   */
  private void deserializeBlobIdAndAssert(short version, String srcBlobIdStr) throws Exception {
    List<BlobId> blobIds = new ArrayList<>();
    blobIds.add(new BlobId(srcBlobIdStr, referenceClusterMap));
    blobIds.add(new BlobId(getStreamFromBase64(srcBlobIdStr), referenceClusterMap));
    blobIds.add(new BlobId(getStreamFromBase64(srcBlobIdStr + "EXTRA"), referenceClusterMap));
    for (BlobId blobId : blobIds) {
      assertEquals("Wrong base-64 ID in blobId: " + blobId, srcBlobIdStr, blobId.getID());
      assertEquals("Wrong blobId version", version, getVersionFromBlobString(blobId.getID()));
      assertBlobIdFieldValues(version, blobId, referenceType, referenceDatacenterId, referenceAccountId,
          referenceContainerId, referencePartitionId, referenceIsEncrypted, referenceDataType);
    }
  }

  /**
   * Convert a base-64 encoded string into a {@link DataInputStream}
   * @param base64String the base-64 encoded {@link String}
   * @return the {@link DataInputStream}
   */
  private DataInputStream getStreamFromBase64(String base64String) {
    return new DataInputStream(new ByteBufferInputStream(ByteBuffer.wrap(Base64.decodeBase64(base64String))));
  }

  /**
   * Asserts a {@link BlobId} against the expected values.
   * @param version The expected version of the blobId.
   * @param blobId The {@link BlobId} to assert.
   * @param type The expected {@link BlobIdType}.
   * @param datacenterId The expected {@code datacenterId}. This will be of no effect if version is set to v1, and the
   *                     expected value will become {@link com.github.ambry.clustermap.ClusterMapUtils#UNKNOWN_DATACENTER_ID}.
   *                     For v2, {@code null} will make the assertion against
   *                     {@link com.github.ambry.clustermap.ClusterMapUtils#UNKNOWN_DATACENTER_ID}.
   * @param accountId The expected {@code accountId}. This will be of no effect if version is set to v1, and the expected
   *                  value will become {@link Account#UNKNOWN_ACCOUNT_ID}. For v2, {@code null} will make the assertion
   *                  against {@link Account#UNKNOWN_ACCOUNT_ID}.
   * @param containerId The expected {@code containerId}. This will be of no effect if version is set to v1, and the
   *                    expected value will become {@link Container#UNKNOWN_CONTAINER_ID}. For v2, {@code null} will make
   *                    the assertion against {@link Container#UNKNOWN_CONTAINER_ID}.
   * @param partitionId The expected partitionId.
   * @param isEncrypted {@code true} expected {@code isEncrypted}. This will be of no effect if version is set to v1 and v2.
   * @throws Exception Any unexpected exception.
   */
  private void assertBlobIdFieldValues(short version, BlobId blobId, BlobIdType type, byte datacenterId,
      short accountId, short containerId, PartitionId partitionId, boolean isEncrypted, BlobDataType blobDataType)
      throws Exception {
    assertTrue("Used unrecognized version", Arrays.asList(BlobId.getAllValidVersions()).contains(version));
    assertEquals("Wrong partition id in blobId: " + blobId, partitionId, blobId.getPartition());
    switch (version) {
      case BLOB_ID_V1:
        assertEquals("Wrong type in blobId: " + blobId, BlobIdType.NATIVE, blobId.getType());
        assertEquals("Wrong datacenter id in blobId: " + blobId, UNKNOWN_DATACENTER_ID, blobId.getDatacenterId());
        assertEquals("Wrong account id in blobId: " + blobId, Account.UNKNOWN_ACCOUNT_ID, blobId.getAccountId());
        assertEquals("Wrong container id in blobId: " + blobId, Container.UNKNOWN_CONTAINER_ID,
            blobId.getContainerId());
        assertFalse("Wrong isEncrypted value in blobId: " + blobId, BlobId.isEncrypted(blobId.getID()));
        assertNull("Expected null blobDataType in blobId", blobId.getBlobDataType());
        break;
      case BLOB_ID_V2:
        assertEquals("Wrong type in blobId: " + blobId, BlobIdType.NATIVE, blobId.getType());
        assertEquals("Wrong datacenter id in blobId: " + blobId, datacenterId, blobId.getDatacenterId());
        assertEquals("Wrong account id in blobId: " + blobId, accountId, blobId.getAccountId());
        assertEquals("Wrong container id in blobId: " + blobId, containerId, blobId.getContainerId());
        assertFalse("Wrong isEncrypted value id in blobId: " + blobId, BlobId.isEncrypted(blobId.getID()));
        assertNull("Expected null blobDataType in blobId", blobId.getBlobDataType());
        break;
      case BLOB_ID_V3:
        assertEquals("Wrong type in blobId: " + blobId, type, blobId.getType());
        assertEquals("Wrong datacenter id in blobId: " + blobId, datacenterId, blobId.getDatacenterId());
        assertEquals("Wrong account id in blobId: " + blobId, accountId, blobId.getAccountId());
        assertEquals("Wrong container id in blobId: " + blobId, containerId, blobId.getContainerId());
        assertFalse("Wrong isEncrypted value id in blobId: " + blobId, BlobId.isEncrypted(blobId.getID()));
        assertNull("Expected null blobDataType in blobId", blobId.getBlobDataType());
        break;
      case BLOB_ID_V4:
        assertEquals("Wrong type in blobId: " + blobId, type, blobId.getType());
        assertEquals("Wrong datacenter id in blobId: " + blobId, datacenterId, blobId.getDatacenterId());
        assertEquals("Wrong account id in blobId: " + blobId, accountId, blobId.getAccountId());
        assertEquals("Wrong container id in blobId: " + blobId, containerId, blobId.getContainerId());
        assertEquals("Wrong isEncrypted value in blobId: " + blobId, isEncrypted, BlobId.isEncrypted(blobId.getID()));
        assertNull("Expected null blobDataType in blobId", blobId.getBlobDataType());
        break;
      case BLOB_ID_V5:
        assertEquals("Wrong type in blobId: " + blobId, type, blobId.getType());
        assertEquals("Wrong datacenter id in blobId: " + blobId, datacenterId, blobId.getDatacenterId());
        assertEquals("Wrong account id in blobId: " + blobId, accountId, blobId.getAccountId());
        assertEquals("Wrong container id in blobId: " + blobId, containerId, blobId.getContainerId());
        assertEquals("Wrong isEncrypted value in blobId: " + blobId, isEncrypted, BlobId.isEncrypted(blobId.getID()));
        assertEquals("Wrong blobDataType value in blobId: " + blobId, blobDataType, blobId.getBlobDataType());
        break;
      default:
        fail("Unrecognized version");
    }
    Pair<Short, Short> accountAndContainer = BlobId.getAccountAndContainerIds(blobId.getID());
    assertEquals("Account id from the id string should be the same as the associated account id", blobId.getAccountId(),
        (short) accountAndContainer.getFirst());
    assertEquals("Container id from the id string should be the same as the associated container id",
        blobId.getContainerId(), (short) accountAndContainer.getSecond());
    assertEquals("Unexpected version returned by BlobID.getVersion()", version, BlobId.getVersion(blobId.getID()));
  }

  /**
   * Gets the version number from a blobId string.
   * @param blobId The blobId string to get version number.
   * @return Version number
   * @throws Exception Any unexpected exception.
   */
  private short getVersionFromBlobString(String blobId) throws Exception {
    DataInputStream dis = new DataInputStream(new ByteBufferInputStream(ByteBuffer.wrap(Base64.decodeBase64(blobId))));
    try {
      return dis.readShort();
    } finally {
      dis.close();
    }
  }

  /**
   * Constructs a {@link BlobId} with random fields and the given version.
   * @param version The version of {@link BlobId} to build
   * @return A {@link BlobId} with random fields and the given version.
   */
  private BlobId getRandomBlobId(short version) {
    byte[] bytes = new byte[2];
    random.nextBytes(bytes);
    random.nextBytes(bytes);
    byte datacenterId = bytes[0];
    short accountId = getRandomShort(random);
    short containerId = getRandomShort(random);
    BlobIdType type = random.nextBoolean() ? BlobIdType.NATIVE : BlobIdType.CRAFTED;
    PartitionId partitionId =
        referenceClusterMap.getWritablePartitionIds(MockClusterMap.DEFAULT_PARTITION_CLASS).get(random.nextInt(3));
    boolean isEncrypted = random.nextBoolean();
    BlobDataType dataType = BlobDataType.values()[random.nextInt(BlobDataType.values().length)];
    return new BlobId(version, type, datacenterId, accountId, containerId, partitionId, isEncrypted, dataType);
  }
}
