/*
 * Copyright (c) 2018-2020 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.komodo.kex.klink;

import com.kadware.komodo.baselib.VirtualAddress;

/**
 * Contains useful info regarding the address at which the program starts.
 */
class ProgramStartInfo {

    final VirtualAddress _vAddress;                 //  level, bdi, and offset of start address
    final int _bankOffset;                          //  offset from the start of the containing bank, of the address
    final int _lcpOffset;                           //  offset from the start of the LC pool, of the address
    final LCPoolSpecification _lcpSpecification;    //  describes the module and LC pool containing the start address

    ProgramStartInfo(
        final VirtualAddress vAddress,
        final int bankOffset,
        final LCPoolSpecification lcpSpecification,
        final int lcpOffset
    ) {
        _vAddress = vAddress;
        _bankOffset = bankOffset;
        _lcpOffset = lcpOffset;
        _lcpSpecification = lcpSpecification;
    }
}
