package com.example.application;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;

import javax.annotation.concurrent.ThreadSafe;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;

/**
 * Реализация семантики "Request-Response" для асинхронного транспорта для Vaadin UI
 * Утилитарный класс, упрощающий взаимодействие между разными workerThreads и конечным Vaadin UI.
 * Позволяет фоновому потоку получить доступ к UI для обновления состояния и прочее.
 * <p>
 * Возвращает {@link CompletableFuture}
 */
@ThreadSafe
public class VaadinBridge {

    private static final String ASYNC_COMMAND_KEY = "AsyncCommand";

    /**
     * NOTES:
     * Данный класс построен с использованием функциональности платформы Vaadin.
     *
     * @see <a href="Для вступительного ознакомления см.">https://vaadin.com/docs/v14/flow/advanced/tutorial-push-access</a>
     * <p>
     * Реализация семантики "Request-Replay" в данном случае необходима для того,
     * чтобы другой поток, который возвращает ответ, знал, какому конкретно {@link UI}
     * следует передать ответ, чтобы потом Vaadin мог эти данные отобразить. Это также
     * обеспечивает согласованное обновление состояний, чтобы
     * не возникал race-condition и прочие проблемы многопоточности, так как
     * {@link UI#access(com.vaadin.flow.server.Command)} обеспечивает эксклюзивный доступ к данным
     * сессии конкретного пользователя, данным View и прочее
     * (по факту синхронизация потоков на уровне Vaadin, что означает, что 2 потока
     * не могут одновременно работать с UI). В противном случае обработка данных
     * в 2-х и более разных потоках могла бы приводить к несогласованному состоянию данных View,
     * данных презентатора, данных сессии и прочее.
     * <p>
     * В общих чертах логика работы выглядит следующим образом: ссылка на
     * оригинальный UI через замыкание передается в лямбду, которая будет вызвана
     * другим потоком (поток, содержащий ответ) и поток впоследствии
     * будет иметь доступ к UI, который был иницатором запроса.
     * <p>
     * Зачем? Ответ заключается в том, что как и любое приложение, имеющее UI
     * (будь то Swing, iOS или Android приложение) обычно подчиняется "single-thread rule".
     * На практике это означает, что компоненты и модели в UI-слое должны создаваться, изменяться
     * и запрашиваться только из какого-то одного mainUI-потока. Естественно, Vaadin не привязывает
     * конкретный поток к одному и только пользователю, это было бы неразумным расходом ресурсов.
     * Подобный сценарий обеспечивается за счет блокировок типа {@link Lock}
     * (более подробно см. исходники {@link VaadinSession#getLockInstance()}).
     * Таким образом код, который необходимо выполнить в рамках UI (обновить значение на странице и прочее)
     * будет гарантированно выполнен в нужном контексте. Однако, обратите внимание, что
     * команда на выполнение нужного блока кода в контексте UI может быть вызвана в
     * другом потоке или позже в текущем потоке, это означает, что локальные переменные
     * worker thread могут не иметь ожидаемых значений при выполнении команды.
     * Из чего вытекает, что {@link UI#access(com.vaadin.flow.server.Command)}
     * - это асинхронная операция.
     * <p>
     * Например, тот же workerThread, который слушает сообщения по кафке и т.п.,
     * может сам стать временно UI-потоком и выполнить все действия, а может
     * создать {@link UICommand} и делегировать это другому потоку.
     */

    private VaadinBridge() {
        throw new UnsupportedOperationException("This utility class");
    }

    public static <R> R execute(@NotNull UICommand<R> command) {
        var future = new CompletableFuture<R>();
        command.execute(future::complete, future::completeExceptionally);
        try {
            return future.get();
        } catch (Exception ex) {
            ExceptionUtils.wrapAndThrow(ex);
        }

        return null;
    }

    /**
     * Метод выполнения команды с использованием {@link Command},
     * обеспечивающий семантику "Request-Replay" для Vaadin UI
     *
     * @param command асинхронная команда для исполнения
     * @param <R>     тип получаемого сообщения
     * @return тип-обещание {@link CompletableFuture}, представляющий обертку над {@link CompletableFuture}
     */
    public static <R> CompletableFuture<R> executeAsync(@NotNull UICommand<R> command) {
        var future = new CompletableFuture<R>();
        Consumer<R> dealWithResult = setContext(future::complete, getCurrentUIVaadin());
        Consumer<Throwable> dealWithException = setContext(future::completeExceptionally, getCurrentUIVaadin());

        command.execute(dealWithResult, dealWithException);
        return future;
    }

    /**
     * @param original исходный callback, который необходимо выполнить для обработки в рамках Vaadin
     * @param vaadinUI ссылка на UI, который ждет ответа от системы
     * @param <R>      тип ожидаемого ответа
     */
    @NotNull
    private static <R> Consumer<R> setAsyncContext(@NotNull Consumer<R> original, @NotNull UI vaadinUI) {
        var executor = ForkJoinPool.commonPool();

        // запускаем обновление UI в отдельном потоке, чтобы не блокировать в определенных случаях
        // поток, который доставляет ответ. Для Кафки это может быть фатально.
        return context -> executor.execute(() ->
                addTask(vaadinUI, vaadinUI.access(() -> original.accept(context))
                )
        );
    }

    /**
     * @param original исходный callback, который необходимо выполнить для обработки в рамках Vaadin
     * @param vaadinUI ссылка на UI, который ждет ответа от системы
     * @param <R>      тип ожидаемого ответа
     */
    @NotNull
    private static <R> Consumer<R> setContext(@NotNull Consumer<R> original, @NotNull UI vaadinUI) {
        return context -> {
            addTask(vaadinUI, vaadinUI.access(() -> original.accept(context)));
        };
    }

    private static void addTask(UI vaadinUI, Future<Void> asyncCommand) {
        getASyncCommand(vaadinUI).add(asyncCommand);
        getCurrentUIVaadin().addDetachListener(event -> asyncCommand.cancel(true));
        getCurrentUIVaadin().addBeforeEnterListener(event -> asyncCommand.cancel(true));
    }

    @SuppressWarnings("unchecked")
    private static Set<Future<Void>> getASyncCommand(UI vaadinUI) {
        synchronized (vaadinUI) {
            var asyncCommand = (Set<Future<Void>>) ComponentUtil.getData(vaadinUI, ASYNC_COMMAND_KEY);
            if (asyncCommand == null) {
                asyncCommand = Collections.synchronizedSet(new HashSet<Future<Void>>());
                ComponentUtil.setData(vaadinUI, ASYNC_COMMAND_KEY, asyncCommand);
            }
            return asyncCommand;
        }
    }

    /**
     * Получить ссылку на UI потока, хранится в threadLocal в платформe Vaadin
     * Ссылку на UI обычно проставляет сам Vaadin, когда происходит
     * обработка на уровне сервлета. Более подробно можно понять, рассмотрев архитектуру Vaadin
     */
    @NotNull
    private static UI getCurrentUIVaadin() {
        var ui = UI.getCurrent();
        if (ui == null) {
            throw new IllegalThreadStateException("Невозможно определить текущий пользовательский UI, к которому необходимо " +
                    "получить доступ. Данный поток не содержит об этом информации, проверьте, что вызов данного метода " +
                    "выполняется потоком, принадлежащий платформе Vaadin или в данном потоке был явно вызван метод UI.setCurrent(vaadinUI)");
        }
        return ui;
    }
}

