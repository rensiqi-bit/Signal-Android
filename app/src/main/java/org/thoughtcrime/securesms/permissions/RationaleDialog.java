package org.thoughtcrime.securesms.permissions;


import android.app.AlertDialog;
import android.content.Context;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.components.Mp02CustomDialog;

public class RationaleDialog {

  private static final String TAG = "RationaleDialog";

  public static AlertDialog createNonMsgDialog(@NonNull Context context,
                                               String title,
                                               int positiveResId,
                                               int negativeResId,
                                               Mp02CustomDialog.Mp02DialogKeyListener positiveListener,
                                               Mp02CustomDialog.Mp02DialogKeyListener negativeListener,
                                               Mp02CustomDialog.Mp02OnBackKeyListener backKeyListener)
  {
    Mp02CustomDialog dialog = new Mp02CustomDialog(context);
    if (title == null || title.equals("")) {
      Log.e(TAG, "Dialog title is NULL!");
    } else {
      dialog.setMessage(title);
    }
    dialog.setPositiveListener(positiveResId, positiveListener);
    dialog.setNegativeListener(negativeResId, negativeListener);
    dialog.setBackKeyListener(backKeyListener);
    return dialog;
  }
}
