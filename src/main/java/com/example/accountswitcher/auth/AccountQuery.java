package com.example.accountswitcher.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Pure account view logic: never changes the vault's saved order. */
public final class AccountQuery {
    public enum Filter {
        ALL("All"), ONLINE("Online"), OFFLINE("Offline"), FAVORITES("Favorites");
        public final String label;
        Filter(String label) { this.label = label; }
        public Filter next() { return values()[(ordinal() + 1) % values().length]; }
    }

    public enum Sort {
        SAVED("Saved order"), NAME("Name A-Z"), RECENT("Last used");
        public final String label;
        Sort(String label) { this.label = label; }
        public Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private AccountQuery() {}

    public static List<Account> select(List<Account> source, String query, Filter filter, Sort sort) {
        String search = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Account> result = new ArrayList<>(source);
        Collections.reverse(result);
        result.removeIf(account -> !account.username.toLowerCase(Locale.ROOT).contains(search)
            || switch (filter) {
                case ALL -> false;
                case ONLINE -> account.type != Account.Type.MICROSOFT;
                case OFFLINE -> account.type != Account.Type.OFFLINE;
                case FAVORITES -> !account.favorite;
            });
        Comparator<Account> order = Comparator.comparing(account -> !account.favorite);
        order = switch (sort) {
            case SAVED -> order;
            case NAME -> order.thenComparing(account -> account.username, String.CASE_INSENSITIVE_ORDER);
            case RECENT -> order.thenComparing(account -> account.lastUsedAt,
                Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
        };
        result.sort(order);
        return result;
    }
}
