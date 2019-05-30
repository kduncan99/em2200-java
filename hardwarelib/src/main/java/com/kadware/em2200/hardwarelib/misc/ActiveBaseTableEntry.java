/*
 * Copyright (c) 2019 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.misc;

import com.kadware.em2200.baselib.Word36;

/**
* Represents an active base table entry.
* It's basically a Word36 with special getters
*/
public class ActiveBaseTableEntry extends Word36 {

    public int getLevel() { return (int) (_value >> 33); }
    public int getBDI() { return (int) (_value >> 18) & 077777; }
    public int getLBDI() { return (int) (_value >> 18); }
    public int getSubsetOffset() { return (int) (_value & 0777777); }
}
