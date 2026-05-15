package com.my.finmon.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.my.finmon.data.dao.AssetDao;
import com.my.finmon.data.dao.BondSchedulePaymentDao;
import com.my.finmon.data.dao.EventDao;
import com.my.finmon.data.dao.ExchangeRateDao;
import com.my.finmon.data.dao.PortfolioValueDao;
import com.my.finmon.data.dao.StockPriceDao;
import com.my.finmon.data.entity.AssetEntity;
import com.my.finmon.data.entity.BondSchedulePaymentEntity;
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
                PortfolioValueSnapshotEntity.class,
                BondSchedulePaymentEntity.class
        },
        version = 8,
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

    public abstract BondSchedulePaymentDao bondSchedulePaymentDao();

    /**
     * v7 → v8: adds the {@code bond_schedule_payment} cache so NBU coupon schedules
     * survive across app launches and the bond being eventually dropped from NBU's
     * feed at maturity. Preserves existing data — only creates the new table + index.
     */
    static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `bond_schedule_payment` ("
                    + "`assetId` INTEGER NOT NULL, "
                    + "`payDate` TEXT NOT NULL, "
                    + "`payType` TEXT NOT NULL, "
                    + "`payVal` TEXT NOT NULL, "
                    + "PRIMARY KEY(`assetId`, `payDate`, `payType`), "
                    + "FOREIGN KEY(`assetId`) REFERENCES `asset`(`id`) "
                    + "ON UPDATE NO ACTION ON DELETE CASCADE)");
            db.execSQL("CREATE INDEX IF NOT EXISTS "
                    + "`index_bond_schedule_payment_assetId` "
                    + "ON `bond_schedule_payment`(`assetId`)");
        }
    };

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
                            .addMigrations(MIGRATION_7_8)
                            // Dev safety net for un-migrated hops. Remove once the app
                            // owns a real migration chain for prod releases.
                            .fallbackToDestructiveMigration()
                            .build();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }
}
