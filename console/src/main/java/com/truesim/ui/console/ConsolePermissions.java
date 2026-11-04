package com.truesim.ui.console;

/**
 * What the player may run (design 5.10): developer commands need the developer tier
 * ({@link ConsoleCommand.Tier#DEVELOPER}), cheats need cheats on. The engine reads them from its settings (a
 * development build, a server's cheat flag); asked when a command is run or completed.
 */
public interface ConsolePermissions {

    /** Everything allowed: development builds and tools. */
    ConsolePermissions ALL = new ConsolePermissions() {
        @Override
        public boolean developer() {
            return true;
        }

        @Override
        public boolean cheats() {
            return true;
        }
    };

    /** A player of a shipped game: player commands only, no cheats. */
    ConsolePermissions PLAYER = new ConsolePermissions() {
        @Override
        public boolean developer() {
            return false;
        }

        @Override
        public boolean cheats() {
            return false;
        }
    };

    /** Whether developer commands may run. */
    boolean developer();

    /** Whether cheat commands may run. */
    boolean cheats();
}
