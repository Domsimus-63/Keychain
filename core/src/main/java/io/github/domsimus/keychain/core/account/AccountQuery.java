package io.github.domsimus.keychain.core.account;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Search, filter and sort for the account list. Never changes the saved order. */
public record AccountQuery(String search, Filter filter, Sort sort) {
    public static final AccountQuery DEFAULT = new AccountQuery("", Filter.ALL, Sort.CUSTOM);

    public enum Filter {
        ALL, MICROSOFT, OFFLINE, FAVORITES;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        boolean accepts(Account account) {
            return switch (this) {
                case ALL -> true;
                case MICROSOFT -> account.isMicrosoft();
                case OFFLINE -> !account.isMicrosoft();
                case FAVORITES -> account.favorite();
            };
        }
    }

    public enum Sort {
        /** The user's own order, changed with move up/down. */
        CUSTOM,
        NAME,
        RECENT;

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public AccountQuery {
        search = search == null ? "" : search;
    }

    public AccountQuery withSearch(String value) {
        return new AccountQuery(value, filter, sort);
    }

    public AccountQuery withFilter(Filter value) {
        return new AccountQuery(search, value, sort);
    }

    public AccountQuery withSort(Sort value) {
        return new AccountQuery(search, filter, value);
    }

    /** Only the custom order can be rearranged; other sorts would immediately undo a move. */
    public boolean allowsReordering() {
        return sort == Sort.CUSTOM && filter == Filter.ALL && search.isBlank();
    }

    /** Pasting a UUID (with or without dashes) finds its account too. */
    private static boolean matchesId(Account account, String needle) {
        String compact = needle.replace("-", "");
        return compact.length() >= 8 && account.id().toString().replace("-", "").startsWith(compact);
    }

    /** Favorites first, then the chosen order. The sort is stable, so ties keep saved order. */
    public List<Account> apply(List<Account> accounts) {
        String needle = search.strip().toLowerCase(Locale.ROOT);
        Comparator<Account> order = Comparator.comparing(account -> !account.favorite());
        order = switch (sort) {
            case CUSTOM -> order;
            case NAME -> order.thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER);
            case RECENT -> order.thenComparing(Account::lastUsed, Comparator.nullsLast(Comparator.<Instant>reverseOrder()));
        };
        return accounts.stream()
            .filter(filter::accepts)
            .filter(account -> needle.isEmpty() || account.name().toLowerCase(Locale.ROOT).contains(needle) || matchesId(account, needle))
            .sorted(order)
            .toList();
    }
}
