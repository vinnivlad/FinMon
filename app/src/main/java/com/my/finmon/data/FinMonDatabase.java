package com.my.finmon.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.EventEntity;
import com.my.finmon.data.entity.ExchangeRateEntity;
import com.my.finmon.data.entity.PortfolioValueSnapshotEntity;
import com.my.finmon.data.entity.StockPriceEntity;

@Database(
        entities = {
                AssetEntity.class,
                EventEntity.class,
                ExchangeRateEntity.class,
                StockPriceEntity.class,
                PortfolioValueSnapshotEntity.class
        },
        version = 1,
        exportSchema = true
)
@TypeConverters(Converters.class)
public abstract class FinMonDatabase extends RoomDatabase {

    public static final String DB_NAME = "finmon.db";

    private static volatile FinMonDatabase INSTANCE;

    public abstract AssetDao assetDao();

    public abstract EventDao eventDao();

    public abstract ExchangeRateDao exchangeRateDao();

    public abstract StockPriceDao stockPriceDao();

    public abstract PortfolioValueDao portfolioValueDao();

    /**
     * Seeds the three cash-pile assets on first DB creation.
     * Runs exactly once — when the SQLite file doesn't yet exist.
     */
    private static final Callback SEED_CALLBACK = new Callback() {
        @Override
        public void onCreate(@NonNull SupportSQLiteDatabase db) {
            super.onCreate(db);
            db.execSQL("INSERT INTO asset (ticker, currency, type) "
                    + "VALUES ('CASH_USD', 'USD', 'CASH')");
            db.execSQL("INSERT INTO asset (ticker, currency, type) "
                    + "VALUES ('CASH_EUR', 'EUR', 'CASH')");
            db.execSQL("INSERT INTO asset (ticker, currency, type) "
                    + "VALUES ('CASH_UAH', 'UAH', 'CASH')");
        }
    };

    public static FinMonDatabase get(Context context) {
        FinMonDatabase local = INSTANCE;
        if (local == null) {
            synchronized (FinMonDatabase.class) {
                local = INSTANCE;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    FinMonDatabase.class,
                                    DB_NAME)
                            .addCallback(SEED_CALLBACK)
                            // Dev only: wipe the DB on any schema change. Remove and
                            // write a proper @Migration once there's data worth keeping.
                            .fallbackToDestructiveMigration()
                            .build();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }
}
