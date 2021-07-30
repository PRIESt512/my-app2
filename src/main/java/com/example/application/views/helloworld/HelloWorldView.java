package com.example.application.views.helloworld;

import com.example.application.VaadinBridge;
import com.example.application.command.WorkImitation;
import com.example.application.views.MainLayout;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

@PageTitle("Hello World")
@Route(value = "hello", layout = MainLayout.class)
@RouteAlias(value = "", layout = MainLayout.class)
public class HelloWorldView extends HorizontalLayout {

    private final TextField name;
    private final Button sayHello;
    private int counter = 0;

    private final UI currentUI = UI.getCurrent(); // без этого работать не будет

    public HelloWorldView() {
        addClassName("hello-world-view");
        name = new TextField("Your name");
        sayHello = new Button("Say hello");
        add(name, sayHello);
        setVerticalComponentAlignment(Alignment.END, name, sayHello);

        sayHello.addClickListener(this::onAsyncEvent);
    }

    private void onVeryStrangeAsyncEvent(ClickEvent<Button> e) {
        try {
            counter++;
            sayHello.setVisible(false);
            currentUI.access(() -> {
                var result = longRunning(name.getValue());
                showSuccess(result + " -> " + counter);
                sayHello.setVisible(true); // #2
            }); // #1

        } catch (Exception ex) {
            showError(ex.getMessage());
        } finally {
            sayHello.setVisible(true); // #2
        }
    }

//    private void onAsyncEvent(ClickEvent<Button> e) {
//        counter++;
//        sayHello.setVisible(false); // #1
//        longRunning(name.getValue(),
//                result -> currentUI.access(() -> { // Захват блокировки UI пользователя
//                    showSuccess(result + " -> " + counter);
//                    sayHello.setVisible(true); // #2
//                }),
//                ex -> currentUI.access(() -> { // Захват блокировки UI пользователя
//                    showError(ex.getMessage());
//                    sayHello.setVisible(true); // #2
//                })
//        );
//    }

    private void onSyncEvent(ClickEvent<Button> e) {
        try {
            counter++;
            sayHello.setVisible(false); // #1
            var result = longRunning(name.getValue());
            showSuccess(result + " -> " + counter);
        } catch (Exception ex) {
            showError(ex.getMessage());
        } finally {
            sayHello.setVisible(true); // #2
        }
    }

    private void onAsyncEvent(ClickEvent<Button> e) {
        counter++;

        sayHello.setVisible(false);
        VaadinBridge
                .executeAsync(new WorkImitation(name.getValue() + " -> " + counter, 5000L))
                .whenComplete(this::accept);
    }

    private void accept(String result, Throwable ex) {
        if (ex != null) {
            showError(ex.getMessage());
        } else {
            showSuccess(result);
        }
        sayHello.setVisible(true);
    }

    private void showError(String error) {
        var notification = new Notification();
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        notification.add(error);

        notification.open();
    }

    private void showSuccess(String result) {
        Notification.show(result);
    }

    private String longRunning(String value) {
        LockSupport.parkNanos(2_000 * 1_000_000);
        return "Hello " + value;
    }

    private void longRunning(@NotNull String value,
                             @NotNull Consumer<String> dealWithResult,
                             @NotNull Consumer<Throwable> dealWithExc) {
        ForkJoinPool.commonPool().execute(() -> {
            try {
                LockSupport.parkNanos(1_000 * 1_000_000);
                dealWithResult.accept("Hello " + value);
            } catch (Exception ex) {
                dealWithExc.accept(ex);
            }
        });
    }
}
