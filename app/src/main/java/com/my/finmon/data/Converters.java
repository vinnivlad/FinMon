package com.my.finmon.data;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

import com.my.finmon.data.model.AssetType;
import com.my.finmon.data.model.Currency;
import com.my.finmon.data.model.EventType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class Converters {

    @TypeConverter
    @Nullable
    public static String fromLocalDate(@Nullable LocalDate value) {
        return value == null ? null : value.toString();
    }

    @TypeConverter
    @Nullable
    public static LocalDate toLocalDate(@Nullable String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    @TypeConverter
    @Nullable
    public static String fromLocalDateTime(@Nullable LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    @TypeConverter
    @Nullable
    public static LocalDateTime toLocalDateTime(@Nullable String value) {
        return value == null ? null : LocalDateTime.parse(value);
    }

    @TypeConverter
    @Nullable
    public static String fromBigDecimal(@Nullable BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    @TypeConverter
    @Nullable
    public static BigDecimal toBigDecimal(@Nullable String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @TypeConverter
    @Nullable
    public static String fromCurrency(@Nullable Currency value) {
        return value == null ? null : value.name();
    }

    @TypeConverter
    @Nullable
    public static Currency toCurrency(@Nullable String value) {
        return value == null ? null : Currency.valueOf(value);
    }

    @TypeConverter
    @Nullable
    public static String fromAssetType(@Nullable AssetType value) {
        return value == null ? null : value.name();
    }

    @TypeConverter
    @Nullable
    public static AssetType toAssetType(@Nullable String value) {
        return value == null ? null : AssetType.valueOf(value);
    }

    @TypeConverter
    @Nullable
    public static String fromEventType(@Nullable EventType value) {
        return value == null ? null : value.name();
    }

    @TypeConverter
    @Nullable
    public static EventType toEventType(@Nullable String value) {
        return value == null ? null : EventType.valueOf(value);
    }
}
