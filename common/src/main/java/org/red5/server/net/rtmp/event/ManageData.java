package org.red5.server.net.rtmp.event;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.server.stream.IStreamData;

import java.io.*;

public abstract class ManageData extends BaseEvent implements IStreamData<AudioData> {

    public ManageData(IoBuffer data, boolean copy) {
        super(Type.STREAM_DATA);
        if (copy) {
            byte[] array = new byte[data.remaining()];
            data.mark();
            data.get(array);
            data.reset();
            setData(array);
        } else {
            setData(data);
        }
    }

    public ManageData(Type type) {
        super(type);
    }

    public abstract void setData(byte[] data) ;

    public abstract void setData(IoBuffer data);

    /**
     * Duplicate this message / event.
     *
     * @return duplicated event
     */
    public ManageData duplicate() throws IOException, ClassNotFoundException {
        AudioData result = new AudioData();
        // serialize
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        writeExternal(oos);
        oos.close();
        // convert to byte array
        byte[] buf = baos.toByteArray();
        baos.close();
        // create input streams
        ByteArrayInputStream bais = new ByteArrayInputStream(buf);
        ObjectInputStream ois = new ObjectInputStream(bais);
        // deserialize
        result.readExternal(ois);
        ois.close();
        bais.close();
        // clone the header if there is one
        if (header != null) {
            result.setHeader(header.clone());
        }
        result.setSourceType(sourceType);
        result.setSource(source);
        result.setTimestamp(timestamp);
        return result;
    }
}
