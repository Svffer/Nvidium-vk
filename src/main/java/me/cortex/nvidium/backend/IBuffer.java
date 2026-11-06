package me.cortex.nvidium.backend;

/**
 * A GPU buffer owned by nvidium. Backend-neutral: the only things the renderer needs to know
 * about a buffer are its size and how to free it. Backend implementations attach their own
 * handles (GL name, VkBuffer + VMA allocation, ...).
 */
public interface IBuffer extends IResource {
    long getSize();
}
