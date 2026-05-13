package io.audit.core;

import java.util.concurrent.CompletableFuture;

public interface AuditLogger extends AutoCloseable {

    /**
     * Schreibt einen Audit-Eintrag asynchron in die Datenbank.
     * <p>
     * <b>Achtung – Backpressure:</b> Die Implementierung ({@link PostgresAuditLogger})
     * führt diesen Aufruf standardmäßig auf einem Virtual Thread aus. Bei hoher Last
     * können mehr virtuelle Threads erzeugt werden, als der zugrundeliegende
     * Connection-Pool gleichzeitig bedienen kann. Die überschüssigen Threads warten
     * dann auf eine freie Verbindung. Überschreitet die Wartezeit das
     * Connection-Timeout (standardmäßig 30 s bei HikariCP), wird eine
     * {@code SQLTransientConnectionException} geworfen, die als
     * {@link AuditLoggingException} in der zurückgegebenen {@code CompletableFuture}
     * landet.
     * <p>
     * Bei erwartbaren Lastspitzen sollte der Connection-Pool ausreichend
     * dimensioniert oder ein eigener {@link java.util.concurrent.Executor} mit
     * begrenzter Parallelität übergeben werden.
     *
     * @param entry der Audit-Eintrag
     * @return eine {@code CompletableFuture}, die completed, wenn der Eintrag
     *         persistiert ist (bzw. exceptionally bei Fehlern)
     */
    CompletableFuture<Void> log(AuditEntry entry);

    @Override
    default void close() {}
}
