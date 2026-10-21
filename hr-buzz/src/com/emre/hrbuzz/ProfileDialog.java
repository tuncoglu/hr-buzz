package com.emre.hrbuzz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * First run: age, sex and weight, and a limit from a clinician if there is one. Without it
 * every zone, calorie and the default alert silently assume a 40-year-old 75 kg man.
 */
final class ProfileDialog {

    private ProfileDialog() {
    }

    /** True until the profile has been entered here or in Settings. */
    static boolean needed(Activity a) {
        SharedPreferences p = Prefs.of(a);
        return !p.getBoolean("profileDone", false) && !p.contains("age");
    }

    static void show(Activity a, Runnable onDone) {
        ScrollView scroll = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 24);
        root.setPadding(pad, Ui.dp(a, 8), pad, 0);
        scroll.addView(root);

        root.addView(Ui.text(a, "Heart rate zones, calories and the default alert are worked "
                + "out from these. They stay on the phone.", 14, Ui.TEXT2));
        EditText age = number(a, root, "Age", "years");
        LinearLayout sexRow = row(a, root, "Sex");
        LinearLayout sex = new LinearLayout(a);
        final boolean[] male = {true};
        final boolean[] sexChosen = {false};
        String[] sexes = {"Male", "Female"};
        for (int i = 0; i < 2; i++) {
            final int index = i;
            TextView chip = Ui.chip(a, sexes[i]);
            chip.setOnClickListener(v -> {
                male[0] = index == 0;
                sexChosen[0] = true;
                Ui.select(sex, index);
            });
            sex.addView(chip);
        }
        sexRow.addView(sex);
        EditText weight = number(a, root, "Weight", "kg");
        EditText limit = number(a, root, "Limit from a doctor", "bpm");
        TextView limitHint = Ui.text(a, "Optional. If you take beta-blockers or have a heart "
                + "condition, enter the limit your doctor or cardiac rehab team gave you: "
                + "age-based estimates can be far too high for you.", 13, Ui.TEXT3);
        limitHint.setPadding(0, Ui.dp(a, 4), 0, 0);
        root.addView(limitHint);
        TextView error = Ui.text(a, "", 14, 0xFFFF6961);
        error.setPadding(0, Ui.dp(a, 8), 0, 0);
        Ui.show(error, false);
        root.addView(error);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("About you")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int ageV = parse(age);
                    int weightV = parse(weight);
                    int limitV = parse(limit);
                    String problem = ageV < 10 || ageV > 100 ? "Enter your age (10–100)."
                            : !sexChosen[0] ? "Choose male or female (for calories)."
                            : weightV != 0 && (weightV < 30 || weightV > 250)
                            ? "Weight should be 30–250 kg, or leave it empty."
                            : limitV != 0 && (limitV < 60 || limitV > 230)
                            ? "A limit should be 60–230 bpm, or leave it empty." : null;
                    if (problem != null) {
                        error.setText(problem);
                        Ui.show(error, true);
                        return;
                    }
                    save(a, ageV, male[0], weightV, limitV);
                    dialog.dismiss();
                    onDone.run();
                }));
        dialog.show();
    }

    private static void save(Activity a, int age, boolean male, int weight, int limit) {
        SharedPreferences p = Prefs.of(a);
        SharedPreferences.Editor e = p.edit().putInt("age", age).putBoolean("male", male)
                .putBoolean("profileDone", true);
        if (weight > 0) {
            e.putInt("weight", weight);
        }
        boolean ownAlert = p.contains("threshold");
        e.apply();
        HrManager hr = HrManager.get(a);
        if (limit > 0) {
            hr.setThreshold(limit);
        } else if (!ownAlert) {
            hr.setThreshold(Training.defaultAlert(Prefs.hrMax(a)));
        }
    }

    private static int parse(EditText input) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LinearLayout row(Activity a, LinearLayout parent, String label) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(a, 10), 0, 0);
        TextView t = Ui.text(a, label, 16, Ui.TEXT);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r);
        return r;
    }

    private static EditText number(Activity a, LinearLayout parent, String label, String unit) {
        LinearLayout r = row(a, parent, label);
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
        input.setGravity(Gravity.CENTER);
        input.setTextSize(20);
        input.setTextColor(Ui.TEXT);
        r.addView(input, new LinearLayout.LayoutParams(Ui.dp(a, 88),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView u = Ui.text(a, unit, 15, Ui.TEXT2);
        u.setMinWidth(Ui.dp(a, 48));
        u.setPadding(Ui.dp(a, 6), 0, 0, 0);
        r.addView(u);
        return input;
    }
}
