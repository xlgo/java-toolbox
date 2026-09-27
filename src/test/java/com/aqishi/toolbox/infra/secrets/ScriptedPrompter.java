package com.aqishi.toolbox.infra.secrets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Prompt strategy for tests: answers from a queue and records what was asked. */
public final class ScriptedPrompter implements SecretPrompter {
    private final Deque<Supplier<Answer>> answers = new ArrayDeque<>();
    public final List<String> asked = new ArrayList<>();

    public ScriptedPrompter then(Supplier<Answer> answer) {
        answers.add(answer);
        return this;
    }

    @Override
    public Answer askBeforeSave(String profileLabel, SecretStore.Status status) {
        asked.add("save:" + profileLabel + ":" + status);
        return next();
    }

    @Override
    public Answer askBeforeConnect(String profileLabel, SecretStore.Status status,
                                   boolean sessionEntryAllowed) {
        asked.add("connect:" + profileLabel + ":" + status + ":" + sessionEntryAllowed);
        return next();
    }

    private Answer next() {
        if (answers.isEmpty()) throw new AssertionError("unexpected prompt");
        return answers.removeFirst().get();
    }
}
