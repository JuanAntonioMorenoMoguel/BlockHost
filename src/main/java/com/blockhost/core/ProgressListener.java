package com.blockhost.core;

/** Recibe avances de tareas largas. fraction en [0,1], o -1 si es indeterminado. */
@FunctionalInterface
public interface ProgressListener {

    void onProgress(String message, double fraction);

    default void log(String message) { onProgress(message, -1); }

    ProgressListener NONE = (m, f) -> { };
}
