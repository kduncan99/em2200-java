/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.exceptions;

import com.kadware.komodo.baselib.types.BlockSize;

/**
 * Exception thrown by a method when an invalid block size is detected
 */
public class InvalidBlockSizeException extends Exception {

    public InvalidBlockSizeException(
        final BlockSize blockSize
    ) {
        super(String.format("Invalid Block Size:%s", String.valueOf(blockSize)));
    }
}
