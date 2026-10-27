package com.enftc.hrbuzz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Report this answer": what is saved when someone flags something the coach wrote, and the
 * text of the email they can send from their own mail app. A report is kept on the phone; it
 * leaves only if the person presses Send in their email app. Pure Java, so it runs in the
 * plain-JVM tests.
 */
public final class Reports {

    /** Where reports are emailed. The same mailbox as the privacy policy. */
    public static final String EMAIL = "privacy@sourdoughcalculator.uk";

    /** Oldest reports are dropped past this many. */
    static final int MAX = 50;
    /** Longest question or answer kept, in characters. */
    static final int MAX_TEXT = 4000;

    public static final String[] REASONS = {
            "Wrong or unsafe advice",
            "Offensive or inappropriate",
            "Made up, or not about training",
            "Something else"};

    private Reports() {
    }

    /** The saved reports with one more at the end (JSON array text). */
    public static String add(String existing, long time, String kind, String reason,
                             String service, String model, String question, String answer) {
        List<Object> list = parse(existing);
        list.add(Json.map("t", time, "kind", kind == null ? "" : kind,
                "reason", reason == null ? "" : reason,
                "service", service == null ? "" : service,
                "model", model == null ? "" : model,
                "question", clip(question), "answer", clip(answer)));
        while (list.size() > MAX) {
            list.remove(0);
        }
        return Json.write(list);
    }

    public static int count(String json) {
        return parse(json).size();
    }

    /** Only the newest report, in the same format. */
    public static String last(String json) {
        List<Object> list = parse(json);
        List<Object> one = new ArrayList<>();
        if (!list.isEmpty()) {
            one.add(list.get(list.size() - 1));
        }
        return Json.write(one);
    }

    public static String subject(int n) {
        return n == 1 ? "HR Buzz: report on a coach answer"
                : "HR Buzz: " + n + " reports on coach answers";
    }

    /** The email text: what these are, a warning to check them, and each report. */
    public static String emailBody(String json) {
        List<Object> list = parse(json);
        StringBuilder sb = new StringBuilder();
        sb.append("These are reports about answers an AI service wrote inside the HR Buzz app. ")
                .append("They were saved on my phone, and I am sending them myself.\n\n")
                .append("Please read what is below before you send it: it can include details of ")
                .append("my training or health. Delete anything you do not want to share.\n");
        int i = 0;
        for (Object o : list) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) {
                continue;
            }
            sb.append("\n--- Report ").append(++i).append(" ---\n");
            sb.append("When: ").append(Instant.ofEpochMilli((long) Json.num(r, "t", 0))).append('\n');
            sb.append("Where: ").append("debrief".equals(Json.str(r, "kind", ""))
                    ? "a session debrief" : "the coach chat").append('\n');
            String service = Json.str(r, "service", "");
            String model = Json.str(r, "model", "");
            if (!service.isEmpty()) {
                sb.append("Service: ").append(service)
                        .append(model.isEmpty() ? "" : ", " + model).append('\n');
            }
            sb.append("Problem: ").append(Json.str(r, "reason", "")).append('\n');
            String question = Json.str(r, "question", "");
            if (!question.isEmpty()) {
                sb.append("What was asked: ").append(question).append('\n');
            }
            sb.append("The answer:\n").append(Json.str(r, "answer", "")).append('\n');
        }
        return sb.toString();
    }

    static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= MAX_TEXT ? t : t.substring(0, MAX_TEXT) + "…";
    }

    private static List<Object> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Json.list(Json.parse(json)));
        } catch (Json.ParseException e) {
            return new ArrayList<>();
        }
    }
}
