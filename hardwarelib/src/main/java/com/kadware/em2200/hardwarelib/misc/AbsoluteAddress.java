/*
 * Copyright (c) 2018 by Kurt Duncan - All Rights Reserved
 */

package com.kadware.em2200.hardwarelib.misc;

/**
* Represents an absolute address - this is a composite value which identifies a particular
* StaticMainStorageProcessor, and an offset from the beginning of the storage of that processor
* which identifies a particular word of storage.
*/
public class AbsoluteAddress {

    /**
     * The UPI (Unique Processor Index) value identifying a particular MSP
     * Range: 0:0x0F
     */
    public short _upi;

    /**
     * Indicates a particular segment - the offset is relative to the segment.
     * For hardware-emulated MSPs, there may be only one or a few segments, and the operating system is
     * responsible for managing memory there-in.
     * For pass-through MSPs, there will be a segment for every memory allocation.  The operating system
     * is responsible for requesting and releasing segments in sizes most convenient for it.
     * Range: 0:0x7FFFFFF (25 bits)
     */
    public int _segment;

    /**
     * A value corresponding to an offset from the start of that MSP's segment.
     * Range: 0:0x7FFFFFFF (35 bits)
     */
    public int _offset;

    /**
     * Constructor
     * <p>
     * @param upi UPI of an MSP
     * @param segment segment of a particular MSP
     * @param offset offset from the beginning of the indicated segment
     */
    public AbsoluteAddress(
        final short upi,
        final int segment,
        final int offset
    ) {
        assert((upi >= 0) && (upi < 16));
        assert((segment & 0xFE000000) == 0);
        assert(_offset >= 0);
        _upi = upi;
        _segment = segment;
        _offset = offset;
    }

    /**
     * Adds another offset to the offset in this object
     * @param offset offset to be added
     */
    public void addOffset(
        final long offset
    ) {
        _offset += offset;
    }

    /**
     * Generate a hash code based solely on the values of this object
     * @return hash code
     */
    @Override
    public int hashCode(
    ) {
        return (new Integer(_upi)).hashCode() ^ (new Integer(_segment)).hashCode() ^ (new Integer((int)_offset)).hashCode();
    }

    /**
     * equals method
     * @param obj comparison object
     * @return true if this object equals the comparison object
     */
    @Override
    public boolean equals(
        final Object obj
    ) {
        if (obj instanceof AbsoluteAddress) {
            AbsoluteAddress comp = (AbsoluteAddress) obj;
            return (_upi == comp._upi) && (_segment == comp._segment) && (_offset == comp._offset);
        } else {
            return false;
        }
    }

    /**
     * Setter
     */
    public void set(
        final short upi,
        final int segment,
        final int offset
    ) {
        _upi = upi;
        _segment = segment;
        _offset = offset;
    }

    /**
     * Setter
     * @param address source value
     */
    public void set(
        final AbsoluteAddress address
    ) {
        _upi = address._upi;
        _segment = address._segment;
        _offset = address._offset;
    }

    @Override
    public String toString(
    ) {
        return String.format("0%o:0%o:%012o", _upi, _segment, _offset);
    }
}
