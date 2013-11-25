package com.github.ambry.clustermap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

// TestDataNode permits DataNode to be constructed with a null Datacenter.
class TestDataNode extends DataNode {
  public TestDataNode(JSONObject jsonObject) throws JSONException {
    super(null, jsonObject);
  }

  @Override
  public void validateDatacenter() {
    // Null OK
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    TestDataNode testDataNode = (TestDataNode) o;

    if (!getHostname().equals(testDataNode.getHostname())) return false;
    if (getPort() != testDataNode.getPort()) return false;
    if (getHardwareState() != testDataNode.getHardwareState()) return false;
    if (getCapacityGB() != testDataNode.getCapacityGB()) return false;

    return true;
  }
}

/**
 * Tests {@link DataNode} class.
 */
public class DataNodeTest {
  private static int diskCount = 10;
  private static long diskCapacityGB = 1000;

  JSONArray getDisks() throws JSONException {
    return TestUtils.getJsonArrayDisks(diskCount, "/mnt", HardwareState.AVAILABLE, diskCapacityGB);
  }

  @Test
  public void basics() throws JSONException {
    JSONObject jsonObject = TestUtils.getJsonDataNode("localhost", 6666, HardwareState.AVAILABLE, getDisks());

    DataNode dataNode = new TestDataNode(jsonObject);

    assertEquals(dataNode.getHostname(), "localhost");
    assertEquals(dataNode.getPort(), 6666);
    assertEquals(dataNode.getHardwareState(), HardwareState.AVAILABLE);

    assertEquals(dataNode.getDisks().size(), diskCount);
    assertEquals(dataNode.getCapacityGB(), diskCount * diskCapacityGB);

    assertEquals(dataNode.toJSONObject().toString(), jsonObject.toString());
    assertEquals(dataNode, new TestDataNode(dataNode.toJSONObject()));
  }

  public void failValidation(JSONObject jsonObject) throws JSONException {
    try {
      new TestDataNode(jsonObject);
      fail("Construction of TestDataNode should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }
  }

  @Test
  public void validation() throws JSONException {
    JSONObject jsonObject;

    try {
      // Null DataNode
      jsonObject = TestUtils.getJsonDataNode("localhost", 6666, HardwareState.AVAILABLE, getDisks());
      new DataNode(null, jsonObject);
      fail("Should have failed validation.");
    } catch (IllegalStateException e) {
      // Expected.
    }

    // Bad hostname
    jsonObject = TestUtils.getJsonDataNode("", 6666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject);

    // Bad hostname (http://tools.ietf.org/html/rfc6761 defines 'invalid' top level domain)
    jsonObject = TestUtils.getJsonDataNode("hostname.invalid", 6666, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject);

    // Bad port (too small)
    jsonObject = TestUtils.getJsonDataNode("localhost", -1, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject);

    // Bad port (too big)
    jsonObject = TestUtils.getJsonDataNode("localhost", 100 * 1000, HardwareState.AVAILABLE, getDisks());
    failValidation(jsonObject);
  }
}
