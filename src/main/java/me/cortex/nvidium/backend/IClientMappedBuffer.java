package me.cortex.nvidium.backend;

/**
 * A host-visible buffer that stays persistently mapped for the lifetime of the object.
 * Used by the upload and download streams.
 */
public interface IClientMappedBuffer extends IBuffer {
    long clientAddress();
}
