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
import org.red5.codec.AudioCodec;
import org.red5.io.ITag;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.stream.IStreamData;

public class AudioData extends ManageData implements IStreamPacket {

    private static final long serialVersionUID = -4102940670913999407L;

    protected IoBuffer data;

    /**
     * Data type
     */
    private byte dataType = TYPE_AUDIO_DATA;

    /**
     * Audio codec
     */
    protected AudioCodec codec;

    /**
     * True if this is configuration data and false otherwise
     */
    protected boolean config;

    /** Constructs a new AudioData. */
    public AudioData() {
        this(IoBuffer.allocate(0).flip());
    }

    public AudioData(IoBuffer data) {
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
        if (data != null && data.limit() > 0) {
            data.mark();
            codec = AudioCodec.valueOfById(((data.get(0) & 0xff) & ITag.MASK_SOUND_FORMAT) >> 4);
            // determine by codec whether or not config data is included
            if (AudioCodec.getConfigured().contains(codec)) {
                config = (data.get() == 0);
            }
            data.reset();
        }
        this.data = data;
    }

    public void setData(byte[] data) {
        setData(IoBuffer.wrap(data));
    }

    public int getCodecId() {
        return codec.getId();
    }

    public boolean isConfig() {
        return config;
    }

    /** {@inheritDoc} */
    @Override
    protected void releaseInternal() {
        if (data != null) {
            data.free();
            data = null;
        }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        byte[] byteBuf = (byte[]) in.readObject();
        if (byteBuf != null) {
            setData(byteBuf);
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
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
        return String.format("Audio - ts: %s length: %s", getTimestamp(), (data != null ? data.limit() : '0'));
    }

}
