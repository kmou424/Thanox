package github.tornaco.practice.honeycomb.locker.ui.setup;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.databinding.ObservableBoolean;
import androidx.databinding.ObservableField;
import androidx.databinding.ObservableInt;
import androidx.lifecycle.AndroidViewModel;

import java.util.Objects;

import github.tornaco.android.thanos.core.app.ThanosManager;
import github.tornaco.android.thanos.core.app.activity.ActivityStackSupervisor;
import github.tornaco.android.thanos.core.util.Timber;

public class SetupViewModel extends AndroidViewModel {

    public enum SetupStage {
        /**
         * 第一次输入
         */
        First,
        /**
         * 第二次输入
         */
        Confirm,
        /**
         * 完成
         */
        Complete
    }

    public static final int SETUP_ERROR_KEY_NOT_MATCH = 1;
    public static final int SETUP_ERROR_KEY_NONE = 0;

    private ObservableField<String> firstKey = new ObservableField<>(),
            secondKey = new ObservableField<>();

    public ObservableField<SetupStage> stage = new ObservableField<>(SetupStage.First);
    ObservableBoolean setupComplete = new ObservableBoolean(false);
    public ObservableInt setupError = new ObservableInt(SETUP_ERROR_KEY_NONE);

    private ActivityStackSupervisor lockerManager;

    public SetupViewModel(@NonNull Application application) {
        super(application);
    }

    public void start() {

    }

    public void cancel() {

    }

    private int getLockMethod() {
        return Objects.requireNonNull(lazyGetLockerManager()).getLockerMethod();
    }

    public void onStartInput() {
        setupError.set(SETUP_ERROR_KEY_NONE);
    }

    public void onInputComplete(String input) {
        Timber.d("onInputComplete %s %s", input, stage.get());
        switch (Objects.requireNonNull(stage.get())) {
            case First:
                firstKey.set(input);
                break;
            case Confirm:
                secondKey.set(input);
                break;
            case Complete:
                break;
            default:
                break;
        }
    }

    public void onInputConfirm() {
        Timber.d("onInputConfirm %s %s %s", firstKey.get(), secondKey.get(), stage.get());
        switch (Objects.requireNonNull(stage.get())) {
            case First:
                stage.set(SetupStage.Confirm);
                break;
            case Confirm:
                if (Objects.requireNonNull(secondKey.get()).equals(firstKey.get())) {
                    stage.set(SetupStage.Complete);
                    onSetupComplete();
                } else {
                    setupError.set(SETUP_ERROR_KEY_NOT_MATCH);
                    stage.set(SetupStage.First);
                    firstKey.set(null);
                    secondKey.set(null);
                }
                break;
            case Complete:
                break;
            default:
                break;
        }
    }

    private void onSetupComplete() {
        Timber.d("onSetupComplete %s %s %s", firstKey.get(), secondKey.get(), stage.get());
        Objects.requireNonNull(lazyGetLockerManager()).setLockerKey(getLockMethod(), firstKey.get());
        setupComplete.set(true);
    }

    private ActivityStackSupervisor lazyGetLockerManager() {
        if (lockerManager != null) return lockerManager;
        lockerManager = ThanosManager.from(getApplication().getApplicationContext())
                .getActivityStackSupervisor();
        return lockerManager;
    }
}
