/*
 * RED5 Open Source Media Server - https://github.com/Red5/ Copyright 2006-2023 by respective authors (see below). All rights reserved. Licensed under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless
 * required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package org.red5.server.net.rtmp.event;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.codec.VideoCodec;
import org.red5.io.ITag;
import org.red5.io.IoConstants;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.stream.IStreamData;

/**
 * Video data event
 */
public class VideoData extends ManageData implements IoConstants, IStreamPacket {

    private static final long serialVersionUID = 5538859593815804830L;

    /**
     * Videoframe type
     */
    public static enum FrameType {
        UNKNOWN, KEYFRAME, INTERFRAME, DISPOSABLE_INTERFRAME, END_OF_SEQUENCE
    }

    /**
     * Video data
     */
    protected IoBuffer data;

    /**
     * Data type
     */
    private byte dataType = TYPE_VIDEO_DATA;

    /**
     * Frame type, unknown by default
     */
    protected FrameType frameType = FrameType.UNKNOWN;

    /**
     * Video codec
     */
    protected VideoCodec codec;

    /**
     * True if this is configuration data and false otherwise
     */
    protected boolean config;

    /**
     * True if this indicates an end-of-sequence and false otherwise
     */
    protected boolean endOfSequence;

    /** Constructs a new VideoData. */
    public VideoData() {
        this(IoBuffer.allocate(0).flip());
    }

    /**
     * Create video data event with given data buffer
     *
     * @param data
     *            Video data
     */
    public VideoData(IoBuffer data) {
        super(Type.STREAM_DATA);
        setData(data);
    }

    /** {@inheritDoc} */
    @Override
    public byte getDataType() {
        return dataType;
    }

    public void setDataType(byte dataType) {
        this.dataType = dataType;
    }

    /** {@inheritDoc} */
    public IoBuffer getData() {
        return data;
    }

    public void setData(IoBuffer data) {
        this.data = data;
        if (data != null && data.limit() > 0) {
            data.mark();
            int firstByte = data.get(0) & 0xff;
            codec = VideoCodec.valueOfById(firstByte & ITag.MASK_VIDEO_CODEC);
            // determine by codec whether or not frame / sequence types are included
            if (VideoCodec.getConfigured().contains(codec)) {
                int secondByte = data.get(1) & 0xff;
                config = (secondByte == 0);
                endOfSequence = (secondByte == 2);
            }
            data.reset();
            int frameType = (firstByte & MASK_VIDEO_FRAMETYPE) >> 4;
            if (frameType == FLAG_FRAMETYPE_KEYFRAME) {
                this.frameType = FrameType.KEYFRAME;
            } else if (frameType == FLAG_FRAMETYPE_INTERFRAME) {
                this.frameType = FrameType.INTERFRAME;
            } else if (frameType == FLAG_FRAMETYPE_DISPOSABLE) {
                this.frameType = FrameType.DISPOSABLE_INTERFRAME;
            } else {
                this.frameType = FrameType.UNKNOWN;
            }
        }
    }

    public void setData(byte[] data) {
        setData(IoBuffer.wrap(data));
    }

    /**
     * Getter for frame type
     *
     * @return Type of video frame
     */
    public FrameType getFrameType() {
        return frameType;
    }

    public int getCodecId() {
        return codec.getId();
    }

    public boolean isConfig() {
        return config;
    }

    public boolean isEndOfSequence() {
        return endOfSequence;
    }

    /** {@inheritDoc} */
    @Override
    protected void releaseInternal() {
        if (data != null) {
            final IoBuffer localData = data;
            // null out the data first so we don't accidentally
            // return a valid reference first
            data = null;
            localData.clear();
            localData.free();
        }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        frameType = (FrameType) in.readObject();
        byte[] byteBuf = (byte[]) in.readObject();
        if (byteBuf != null) {
            setData(byteBuf);
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        out.writeObject(frameType);
        if (data != null) {
            if (data.hasArray()) {
                out.writeObject(data.array());
            } else {
                byte[] array = new byte[data.remaining()];
                data.mark();
                data.get(array);
                data.reset();
                out.writeObject(array);
            }
        } else {
            out.writeObject(null);
        }
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return String.format("Video - ts: %s length: %s", getTimestamp(), (data != null ? data.limit() : '0'));
    }

}
