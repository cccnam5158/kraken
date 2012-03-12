package org.krakenapps.logstorage.engine;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.krakenapps.confdb.ConfigService;
import org.krakenapps.logstorage.DiskLackAction;
import org.krakenapps.logstorage.DiskLackCallback;
import org.krakenapps.logstorage.DiskSpaceType;
import org.krakenapps.logstorage.LogStorage;
import org.krakenapps.logstorage.LogStorageMonitor;
import org.krakenapps.logstorage.LogTableRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(name = "logstorage-monitor")
@Provides
public class LogStorageMonitorEngine implements LogStorageMonitor {
	private static final String DEFAULT_MIN_FREE_SPACE_TYPE = DiskSpaceType.Percentage.toString();
	private static final int DEFAULT_MIN_FREE_SPACE_VALUE = 10;
	private static final String DEFAULT_DISK_LACK_ACTION = DiskLackAction.StopLogging.toString();

	private final Logger logger = LoggerFactory.getLogger(LogStorageMonitorEngine.class.getName());

	@Requires
	private LogTableRegistry tableRegistry;

	@Requires
	private LogStorage storage;

	@Requires
	private ConfigService conf;

	private DiskSpaceType minFreeSpaceType;
	private int minFreeSpaceValue;
	private DiskLackAction diskLackAction;
	private Set<DiskLackCallback> diskLackCallbacks = new HashSet<DiskLackCallback>();

	public LogStorageMonitorEngine() {
		minFreeSpaceType = DiskSpaceType.valueOf(getStringParameter(Constants.MinFreeDiskSpaceType, DEFAULT_MIN_FREE_SPACE_TYPE));
		minFreeSpaceValue = getIntParameter(Constants.MinFreeDiskSpaceValue, DEFAULT_MIN_FREE_SPACE_VALUE);
		diskLackAction = DiskLackAction.valueOf(getStringParameter(Constants.DiskLackAction, DEFAULT_DISK_LACK_ACTION));
	}

	@Override
	public int getMinFreeSpaceValue() {
		return minFreeSpaceValue;
	}

	@Override
	public DiskSpaceType getMinFreeSpaceType() {
		return minFreeSpaceType;
	}

	@Override
	public void setMinFreeSpace(int value, DiskSpaceType type) {
		if (type == DiskSpaceType.Percentage) {
			if (value <= 0 || value >= 100)
				throw new IllegalArgumentException("invalid value");
		} else if (type == DiskSpaceType.Megabyte) {
			if (value <= 0)
				throw new IllegalArgumentException("invalid value");
		} else if (type == null)
			throw new IllegalArgumentException("type cannot be null");

		this.minFreeSpaceType = type;
		this.minFreeSpaceValue = value;

		ConfigUtil.set(conf, Constants.MinFreeDiskSpaceType, type.toString());
		ConfigUtil.set(conf, Constants.MinFreeDiskSpaceValue, Integer.toString(value));
	}

	@Override
	public DiskLackAction getDiskLackAction() {
		return diskLackAction;
	}

	@Override
	public void setDiskLackAction(DiskLackAction action) {
		if (action == null)
			throw new IllegalArgumentException("action cannot be null");

		this.diskLackAction = action;

		ConfigUtil.set(conf, Constants.DiskLackAction, action.toString());
	}

	@Override
	public void registerDiskLackCallback(DiskLackCallback callback) {
		diskLackCallbacks.add(callback);
	}

	@Override
	public void unregisterDiskLackCallback(DiskLackCallback callback) {
		diskLackCallbacks.remove(callback);
	}

	private boolean isDiskLack() {
		File dir = storage.getDirectory();
		long usable = dir.getUsableSpace();
		long total = dir.getTotalSpace();

		logger.trace("kraken logstorage: check disk lack, {} {}", minFreeSpaceValue, minFreeSpaceType);
		if (minFreeSpaceType == DiskSpaceType.Percentage) {
			int percent = (int) (usable * 100 / total);
			if (percent < minFreeSpaceValue) {
				logger.warn("kraken logstorage: setted minimum free space {}%, now free space {}%", minFreeSpaceValue, percent);
				return true;
			}
		} else if (minFreeSpaceType == DiskSpaceType.Megabyte) {
			int mega = (int) (usable / 1048576);
			if (mega < minFreeSpaceValue) {
				logger.warn("kraken logstorage: setted minimum free space {} MB, now free space {} MB", minFreeSpaceValue, mega);
				return true;
			}
		}

		return false;
	}

	@Override
	public void run() {
		if (isDiskLack()) {
			logger.warn("kraken logstorage: not enough disk space");
			if (diskLackAction == DiskLackAction.StopLogging) {
				logger.info("kraken logstorage: stop logging");
				storage.stop();
			} else if (diskLackAction == DiskLackAction.RemoveOldLog) {
				List<LogFile> files = new ArrayList<LogFile>();
				for (String tableName : tableRegistry.getTableNames()) {
					for (Date date : storage.getLogDates(tableName))
						files.add(new LogFile(tableName, date));
				}
				Collections.sort(files, new LogFileComparator());
				int index = 0;
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
				do {
					if (index >= files.size()) {
						logger.info("kraken logstorage: stop logging");
						storage.stop();
						break;
					}
					LogFile lf = files.get(index++);
					logger.info("kraken logstorage: remove old log, table {}, {}", lf.tableName, sdf.format(lf.date));
					lf.remove();
				} while (isDiskLack());

				for (DiskLackCallback callback : diskLackCallbacks)
					callback.callback();
			}
		}
	}

	private String getStringParameter(Constants key, String defaultValue) {
		String value = ConfigUtil.get(conf, key);
		if (value != null)
			return value;
		return defaultValue;
	}

	private int getIntParameter(Constants key, int defaultValue) {
		String value = ConfigUtil.get(conf, key);
		if (value != null)
			return Integer.valueOf(value);
		return defaultValue;
	}

	private class LogFile {
		private String tableName;
		private Date date;
		private File index;
		private File data;

		private LogFile(String tableName, Date date) {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
			File tableDir = storage.getTableDirectory(tableName);

			this.tableName = tableName;
			this.date = date;
			this.index = new File(tableDir, sdf.format(date) + ".idx");
			this.data = new File(tableDir, sdf.format(date) + ".dat");
		}

		public void remove() {
			index.delete();
			data.delete();
		}
	}

	private class LogFileComparator implements Comparator<LogFile> {
		@Override
		public int compare(LogFile o1, LogFile o2) {
			return o1.date.compareTo(o2.date);
		}
	}
}