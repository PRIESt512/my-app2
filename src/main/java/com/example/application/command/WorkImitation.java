package com.example.application.command;

import com.example.application.UICommand;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public class WorkImitation implements UICommand<String> {

    private final String input;
    private final long delay;

    public WorkImitation(String input, long delay) {
        this.input = input;
        this.delay = delay;
    }

    @Override
    public void execute(@NotNull Consumer<String> dealWithResult, @NotNull Consumer<Throwable> dealWithException) {
        CompletableFuture
                .supplyAsync(() -> {
                    longRunning();

                    return "Привет " + input;
                })
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        dealWithException.accept(ex);
                    } else {
                        dealWithResult.accept(result);
                    }
                });
    }

    private void longRunning() {
        LockSupport.parkNanos(delay * 1_000_000);
    }
}
