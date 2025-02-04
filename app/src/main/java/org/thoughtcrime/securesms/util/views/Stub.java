package org.thoughtcrime.securesms.util.views;


import android.view.View;
import android.view.ViewStub;

import androidx.annotation.NonNull;

import java.util.Objects;

public class Stub<T extends View> {

  private ViewStub viewStub;
  private T        view;

  public Stub(@NonNull ViewStub viewStub) {
    this.viewStub = viewStub;
  }

  public T get() {
    if (view == null) {
      view = (T)viewStub.inflate();
      viewStub = null;
    }

    return view;
  }
  public @NonNull T require() {
    return Objects.requireNonNull(get());
  }

  public boolean isResolved() {
    return viewStub != null;
  }

  public boolean resolved() {
    return view != null;
  }

  public void setVisibility(int visibility) {
    if (resolved() || visibility == View.VISIBLE) {
      get().setVisibility(visibility);
    }
  }

}
