/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.hadoop.io;

import com.google.common.annotations.VisibleForTesting;
import com.palantir.hadoop.cipher.CipherStreamSupplier;
import com.palantir.hadoop.cipher.CipherStreamSupplierImpl;
import com.palantir.hadoop.cipher.SeekableCipher;
import com.palantir.io.SeekableInput;
import com.palantir.io.SeekableInputStream;
import java.io.IOException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import org.apache.commons.io.IOUtils;

public final class SeekableCipherInput implements SeekableInput {

    private final SeekableInput delegate;
    private final SeekableCipher seekableCipher;
    private final CipherStreamSupplier supplier;

    private CipherInputStream decryptedStream;
    private long decryptedStreamPos;

    public SeekableCipherInput(SeekableInput delegate, SeekableCipher cipher) {
        this(delegate, cipher, new CipherStreamSupplierImpl());
    }

    @VisibleForTesting
    SeekableCipherInput(SeekableInput delegate, SeekableCipher cipher, CipherStreamSupplier supplier) {
        this.delegate = delegate;
        this.seekableCipher = cipher;
        this.supplier = supplier;
        decryptedStream = supplier.getInputStream(new SeekableInputStream(delegate),
                cipher.initCipher(Cipher.DECRYPT_MODE));
        decryptedStreamPos = 0L;
    }

    /**
     * Seeking can only be done to a block offset such that pos % blockSize == 0. "AES/CBC" must be updated with the
     * previous encrypted block in order to properly decrypt after seeking. It should therefore be seeked to block n - 1
     * and then updated by one block in order to be initialized correctly.
     */
    @Override
    public void seek(long pos) throws IOException {
        int blockSize = seekableCipher.getBlockSize();

        // If pos is in the first block then seek to 0 and skip pos bytes
        // else seek to block n - 1 where block n is the block containing the byte at offset pos
        // in order to initialize the Cipher with the previous encrypted block
        final long prevBlock;
        final int bytesToSkip;
        if (pos < blockSize) {
            prevBlock = 0;
            bytesToSkip = (int) pos;
        } else {
            prevBlock = pos / blockSize - 1;
            bytesToSkip = (int) (pos % blockSize + blockSize);
        }

        long prevBlockOffset = prevBlock * blockSize;
        Cipher cipher = seekableCipher.seek(prevBlockOffset);
        delegate.seek(prevBlockOffset);

        // Need a new cipher stream since seeking the stream and cipher invalidate the cipher stream's buffer
        decryptedStream = supplier.getInputStream(new SeekableInputStream(delegate), cipher);

        // Skip to the byte offset in the block where 'pos' is located
        byte[] skip = new byte[bytesToSkip];
        IOUtils.readFully(decryptedStream, skip);
        decryptedStreamPos = pos;
    }

    @Override
    public long getPos() {
        return decryptedStreamPos;
    }

    @Override
    public int read() throws IOException {
        int byteRead = decryptedStream.read();
        if (byteRead != -1) {
            decryptedStreamPos += 1;
        }
        return byteRead;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int bytesRead = decryptedStream.read(buffer, offset, length);
        if (bytesRead != -1) {
            decryptedStreamPos += bytesRead;
        }
        return bytesRead;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
        // TODO (davids) really this should close decryptedStream, but https://bugs.openjdk.java.net/browse/JDK-8064546
        // causes "java.io.IOException: javax.crypto.BadPaddingException: Given final block not properly padded" and
        // is not fixed until Java 7u85 (not publicly available) and Java 8u51.
        // decryptedStream.close();
    }

}