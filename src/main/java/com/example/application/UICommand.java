package com.example.application;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Описывает команду для выполнения асинхронных задач на фронте
 *
 * @param <R> тип ответного сообщения
 */
public interface UICommand<R> {
    void execute(@NotNull Consumer<R> dealWithResult, @NotNull Consumer<Throwable> dealWithException);
}