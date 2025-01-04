/*
 * Copyright (c) 2024 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.rpc.messages;

import com.alibaba.fluss.annotation.Internal;
import com.alibaba.fluss.record.send.WritableOutput;
import com.alibaba.fluss.shaded.netty4.io.netty.buffer.ByteBuf;

/**
 * An object that can serialize itself. The serialization protocol is versioned. Messages also
 * implement toString, equals, and hashCode.
 */
// 可以序列化自身的对象。序列化协议是版本控制的。消息还实现了 toString、equals 和 hashCode。
@Internal
public interface ApiMessage {

    /** Gets the total serialized byte array size of the message. */
    // 获取报文序列化字节数组的总大小。
    int totalSize();

    /**
     * Get the total "zero-copy" size of the message. This is the summed total of all fields which
     * have a type of 'bytes' with 'zeroCopy' enabled.
     *
     * @return total size of zero-copy data in the message
     */
    // 获取报文的 "零拷贝 "总大小。这是启用了 "零拷贝 "且类型为 "字节 "的所有字段的总和。
    int zeroCopySize();

    /**
     * Size excluding zero copy fields as specified by {@link #zeroCopySize}. This is typically the
     * size of the byte buffer used to serialize messages.
     */
    // 不包括zeroCopySize 指定的零拷贝字段的大小。这通常是用于序列化报文的字节缓冲区的大小。
    default int sizeExcludingZeroCopy() {
        return totalSize() - zeroCopySize();
    }

    /**
     * Deserialize the message from the given {@link ByteBuf}. The deserialization happens lazily
     * (i.e. zero-copy) only for {@code "[optional|required] bytes records = ?"} (nested) fields. If
     * there is any lazy deserialization happens, the {@link #isLazilyParsed()} returns true.
     *
     * <p>Note: the current message will hold the reference of {@link ByteBuf}, please remember to
     * release the {@link ByteBuf} until the message has been fully consumed.
     */
    // 从给定的ByteBuf 反序列化信息。
    // 仅对"[可选|必需]字节记录 = ?"（嵌套）字段进行懒惰反序列化（即零拷贝）。
    // 如果发生了懒惰反序列化，isLazilyParsed()将返回 true。
    // 注意：当前报文将持有ByteBuf 的引用，请记住在报文被完全读取之前释放ByteBuf。
    void parseFrom(ByteBuf buffer, int size);

    /**
     * Returns true if there is any {@code "[optional|required] bytes records = ?"} (nested) fields.
     * These fields will be lazily deserialized when {@link #parseFrom(ByteBuf, int)} is called.
     */
    // 如果存在"[可选|必需] 字节记录 = ?"（嵌套）字段，则返回 true。这些字段将在调用parseFrom(ByteBuf, int)时被懒散地反序列化。
    boolean isLazilyParsed();

    /**
     * Deserialize the message from the given byte array.
     *
     * <p>Note: the current message will hold the reference of {@code byte[]}, modify the {@code
     * byte[]} will make the message corrupt.
     */
    // 根据给定的字节数组反序列化信息。
    // 注意：当前信息将持有byte[] 的引用，修改byte[]将使信息损坏。
    void parseFrom(byte[] a);

    /** Serialize the message into the given {@link ByteBuf}. */
    // 将信息序列化到给定的ByteBuf 中。
    int writeTo(ByteBuf buffer);

    /** Serialize the message into the given {@link WritableOutput}. */
    // 将信息序列化到给定的WritableOutput 中。
    void writeTo(WritableOutput output);

    /** Serialize the message into byte array. */
    // 将信息序列化为字节数组。
    byte[] toByteArray();
}
