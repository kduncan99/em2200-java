/*
 * Copyright (c) 2018-2019 by Kurt Duncan - All Rights Reserved
 */
package com.kadware.komodo.minalib;

/**
 * Represents the storage controlled by a particular LocationCounterPool, within a particular Module
 */
class LocationCounterPool {

    final boolean _needsExtendedMode;
    final RelocatableWord36[] _storage;

    LocationCounterPool(
        final RelocatableWord36[] storage,
        final boolean needsExtendedMode
    ) {
        _storage = storage;
        _needsExtendedMode = needsExtendedMode;
    }
}
