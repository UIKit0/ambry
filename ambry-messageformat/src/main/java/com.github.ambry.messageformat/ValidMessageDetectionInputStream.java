package com.github.ambry.messageformat;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.github.ambry.store.MessageInfo;
import com.github.ambry.store.StoreKey;
import com.github.ambry.store.StoreKeyFactory;
import com.github.ambry.utils.SystemTime;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * InputStream that skips invalid blobs based on some validation criteria.
 * For now, the check only supports detection of message corruption
 */
public class ValidMessageDetectionInputStream extends InputStream {
  private int validSize;
  private final Logger logger;
  private ByteBuffer byteBuffer;
  private boolean hasInvalidMessage;
  private List<MessageInfo> validMessageInfoList;

  //metrics
  public Histogram messageFormatValidationTime;
  public Histogram messageFormatBatchValidationTime;

  /**
   * @param stream The stream from which bytes need to be read. If the underlying stream is SocketInputStream, it needs
   *               to be blocking
   * @param messageInfoList List of MessageInfo which contains details about the messages in the stream
   * @param storeKeyFactory factory which is used to read the key from the stream
   * @param metricRegistry Metric register to register metrics
   * @throws java.io.IOException
   */
  public ValidMessageDetectionInputStream(InputStream stream, List<MessageInfo> messageInfoList,
      StoreKeyFactory storeKeyFactory, MetricRegistry metricRegistry)
      throws IOException {
    this.logger = LoggerFactory.getLogger(getClass());
    messageFormatValidationTime = metricRegistry
        .histogram(MetricRegistry.name(ValidMessageDetectionInputStream.class, "MessageFormatValidationTime"));
    messageFormatBatchValidationTime = metricRegistry
        .histogram(MetricRegistry.name(ValidMessageDetectionInputStream.class, "MessageFormatBatchValidationTime"));
    validSize = 0;
    hasInvalidMessage = false;
    validMessageInfoList = new ArrayList<MessageInfo>();

    // check for empty list
    if (messageInfoList.size() == 0) {
      byteBuffer = ByteBuffer.allocate(0);
      return;
    }

    int totalMessageListSize = 0;
    for (MessageInfo info : messageInfoList) {
      totalMessageListSize += info.getSize();
    }

    byte[] data = new byte[totalMessageListSize];
    long startTime = SystemTime.getInstance().milliseconds();
    for (MessageInfo msgInfo : messageInfoList) {
      int msgSize = (int) msgInfo.getSize();
      stream.read(data, validSize, msgSize);
      if (checkForMessageValidity(data, validSize, msgSize, storeKeyFactory)) {
        validSize += msgSize;
        validMessageInfoList.add(msgInfo);
      } else {
        hasInvalidMessage = true;
      }
    }
    messageFormatBatchValidationTime.update(SystemTime.getInstance().milliseconds() - startTime);
    byteBuffer = ByteBuffer.wrap(data, 0, validSize);
  }

  /**
   * Returns the total size of all valid messages that could be read from the stream
   * @return validSize
   */
  public int getSize() {
    return validSize;
  }

  @Override
  public int read()
      throws IOException {
    if (!byteBuffer.hasRemaining()) {
      return -1;
    }
    return byteBuffer.get() & 0xFF;
  }

  @Override
  public int read(byte[] bytes, int offset, int length)
      throws IOException {
    int count = Math.min(byteBuffer.remaining(), length);
    if (count == 0) {
      return -1;
    }
    byteBuffer.get(bytes, offset, count);
    return count;
  }

  /**
   * Whether the stream has invalid messages or not
   * @return
   */
  public boolean hasInvalidMessages() {
    return hasInvalidMessage;
  }

  public List<MessageInfo> getValidMessageInfoList() {
    return validMessageInfoList;
  }

  /**
   * Ensures blob validity of the blob in the given input stream. For now, blobs are checked for message corruption
   * @param data against which validation has to be done
   * @param size total size of the message expected
   * @param currentOffset Current offset at which the data has to be read from the given byte array
   * @param storeKeyFactory StoreKeyFactory used to get store key
   * @return true if message is valid and false otherwise
   * @throws IOException
   */
  private boolean checkForMessageValidity(byte[] data, int currentOffset, long size,
      StoreKeyFactory storeKeyFactory)
      throws IOException {
    StringBuilder strBuilder = new StringBuilder();
    boolean isValid = false;
    int startOffset = currentOffset;
    long startTime = SystemTime.getInstance().milliseconds();
    try {
      int availableBeforeParsing = (int) size;
      ByteBuffer headerVersion =
          ByteBuffer.wrap(data, currentOffset, MessageFormatRecord.Version_Field_Size_In_Bytes);
      currentOffset += MessageFormatRecord.Version_Field_Size_In_Bytes;
      size -= MessageFormatRecord.Version_Field_Size_In_Bytes;
      short version = headerVersion.getShort();
      if (version == 1) {
        ByteBuffer headerBuffer = ByteBuffer.allocate(MessageFormatRecord.MessageHeader_Format_V1.getHeaderSize());
        headerBuffer.putShort(version);
        headerBuffer.put(data, currentOffset, headerBuffer.capacity() - MessageFormatRecord.Version_Field_Size_In_Bytes);
        headerBuffer.position(headerBuffer.capacity());
        headerBuffer.flip();
        currentOffset += MessageFormatRecord.MessageHeader_Format_V1.getHeaderSize()
            - MessageFormatRecord.Version_Field_Size_In_Bytes;
        size -= MessageFormatRecord.MessageHeader_Format_V1.getHeaderSize()
            - MessageFormatRecord.Version_Field_Size_In_Bytes;
        MessageFormatRecord.MessageHeader_Format_V1 header = new MessageFormatRecord.MessageHeader_Format_V1(headerBuffer);
        strBuilder.append("Header - version ").append(header.getVersion());
        strBuilder.append(" Message Size ").append(header.getMessageSize());
        strBuilder.append(" Current Offset ").append(startOffset);
        strBuilder.append(" BlobPropertiesRelativeOffset ").append(header.getBlobPropertiesRecordRelativeOffset());
        strBuilder.append(" UserMetadataRelativeOffset ").append(header.getUserMetadataRecordRelativeOffset());
        strBuilder.append(" DataRelativeOffset ").append(header.getBlobRecordRelativeOffset());
        strBuilder.append(" Crc ").append(header.getCrc());

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data, currentOffset, (int) size);
        StoreKey storeKey = storeKeyFactory.getStoreKey(new DataInputStream(byteArrayInputStream));
        strBuilder.append("; Id - ").append(storeKey.getID());
        if (header.getBlobPropertiesRecordRelativeOffset()
            != MessageFormatRecord.Message_Header_Invalid_Relative_Offset) {
          BlobProperties props = MessageFormatRecord.deserializeBlobProperties(byteArrayInputStream);
          strBuilder.append("; Blob properties - blobSize  ").append(props.getBlobSize()).append(" serviceId ")
              .append(props.getServiceId());
          ByteBuffer metadata = MessageFormatRecord.deserializeUserMetadata(byteArrayInputStream);
          strBuilder.append("; Metadata - size ").append(metadata.capacity());
          BlobOutput output = MessageFormatRecord.deserializeBlob(byteArrayInputStream);
          strBuilder.append("; Blob - size ").append(output.getSize());
        } else {
          throw new IllegalStateException("Message cannot be a deleted record ");
        }
        logger.trace(strBuilder.toString());
        if (availableBeforeParsing - byteArrayInputStream.available() != size) {
          logger.error("Parsed message size " + (byteArrayInputStream.available() - availableBeforeParsing)
              + " is not equivalent to the size in message info " + size);
          isValid = false;
        }
        logger.trace("Message successfully read {} ", strBuilder);
        isValid = true;
      } else {
        throw new IllegalStateException("Header version not supported " + version);
      }
    } catch (IllegalArgumentException e) {
      logger.error("Illegal argument exception thrown at " + startOffset + " and exception: ", e);
    } catch (IllegalStateException e) {
      logger.error("Illegal state exception thrown at " + startOffset + " and exception: ", e);
      throw e;
    } catch (MessageFormatException e) {
      logger.error("MessageFormat exception thrown at " + startOffset + " and exception: ", e);
    } catch (EOFException e) {
      logger.error("EOFException thrown at " + currentOffset, e);
      throw e;
    } finally {
      messageFormatValidationTime.update(SystemTime.getInstance().milliseconds() - startTime);
    }
    return isValid;
  }
}

