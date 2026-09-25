package com.introvertdreams.app;

import android.content.Intent;

/** Developer edition: dashboard opens the AI Agent. */
final class Edition {
    static void openAgent(MainActivity a) { a.startActivity(new Intent(a, AgentActivity.class)); }
}
