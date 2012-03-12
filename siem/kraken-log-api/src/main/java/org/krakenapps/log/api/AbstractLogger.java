/*
 * Copyright 2010 NCHOVY
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.krakenapps.log.api;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractLogger implements Logger, Runnable {
	private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AbstractLogger.class.getName());
	private static final int INFINITE = 0;
	private String fullName;
	private String namespace;
	private String name;
	private String factoryFullName;
	private String factoryNamespace;
	private String factoryName;
	private String description;
	private volatile LogPipe[] pipes;
	private Object updateLock = new Object();
	private Thread t;
	private int interval;
	private Properties config;

	private volatile LoggerStatus status = LoggerStatus.Stopped;
	private volatile boolean doStop = false;
	private volatile boolean stopped = true;

	protected volatile Date lastLogDate = null;
	protected volatile Date lastRunDate = null;
	protected AtomicLong logCounter;

	private Set<LoggerEventListener> eventListeners;

	public AbstractLogger(String name, String description, LoggerFactory loggerFactory) {
		this(name, description, loggerFactory, new Properties());
	}

	public AbstractLogger(String name, String description, LoggerFactory loggerFactory, Properties config) {
		this("local", name, loggerFactory.getNamespace(), loggerFactory.getName(), description, config);
	}

	public AbstractLogger(String namespace, String name, String description, LoggerFactory loggerFactory) {
		this(namespace, name, description, loggerFactory, new Properties());
	}

	public AbstractLogger(String namespace, String name, String description, LoggerFactory loggerFactory,
			Properties config) {
		this(namespace, name, loggerFactory.getNamespace(), loggerFactory.getName(), description, config);
	}

	public AbstractLogger(String namespace, String name, String description, LoggerFactory loggerFactory,
			long logCount, Date lastLogDate, Properties config) {
		this(namespace, name, loggerFactory.getNamespace(), loggerFactory.getName(), description, logCount,
				lastLogDate, config);
	}

	public AbstractLogger(String namespace, String name, String factoryNamespace, String factoryName, Properties config) {
		this(namespace, name, factoryNamespace, factoryName, "", config);
	}

	public AbstractLogger(String namespace, String name, String factoryNamespace, String factoryName,
			String description, Properties config) {
		this(namespace, name, factoryNamespace, factoryName, description, 0, null, config);
	}

	public AbstractLogger(String namespace, String name, String factoryNamespace, String factoryName,
			String description, long logCount, Date lastLogDate, Properties config) {
		// logger info
		this.namespace = namespace;
		this.name = name;
		this.fullName = namespace + "\\" + name;
		this.description = description;
		this.config = config;

		// logger factory info
		this.factoryNamespace = factoryNamespace;
		this.factoryName = factoryName;
		this.factoryFullName = factoryNamespace + "\\" + factoryName;

		this.logCounter = new AtomicLong(logCount);
		this.lastLogDate = lastLogDate;
		this.pipes = new LogPipe[0];

		this.eventListeners = Collections.newSetFromMap(new ConcurrentHashMap<LoggerEventListener, Boolean>());
	}

	@Override
	public String getFullName() {
		return fullName;
	}

	@Override
	public String getNamespace() {
		return namespace;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getFactoryFullName() {
		return factoryFullName;
	}

	@Override
	public String getFactoryName() {
		return factoryName;
	}

	@Override
	public String getFactoryNamespace() {
		return factoryNamespace;
	}

	@Override
	public String getDescription() {
		return description;
	}

	@Override
	public Date getLastLogDate() {
		return lastLogDate;
	}

	@Override
	public Date getLastRunDate() {
		return lastRunDate;
	}

	@Override
	public long getLogCount() {
		return logCounter.get();
	}

	@Override
	public boolean isRunning() {
		return !stopped;
	}

	@Override
	public LoggerStatus getStatus() {
		return status;
	}

	@Override
	public int getInterval() {
		return interval;
	}

	@Override
	public void start(int interval) {
		if (!stopped)
			throw new IllegalStateException("logger is already running");

		status = LoggerStatus.Starting;

		this.interval = interval;

		t = new Thread(this, "Logger [" + fullName + "]");
		t.start();

		status = LoggerStatus.Running;

		// invoke event callbacks
		for (LoggerEventListener callback : eventListeners) {
			try {
				callback.onStart(this);
			} catch (Exception e) {
				log.warn("logger callback should not throw any exception", e);
			}
		}
	}

	@Override
	public void stop() {
		stop(INFINITE);
	}

	@Override
	public void stop(int maxWaitTime) {
		if (t == null || t.isAlive() == false)
			return;

		status = LoggerStatus.Stopping;

		doStop = true;
		t.interrupt();

		long begin = new Date().getTime();

		try {
			while (true) {
				if (stopped)
					break;

				if (maxWaitTime != 0 && new Date().getTime() - begin > maxWaitTime)
					break;

				Thread.sleep(50);
			}
		} catch (InterruptedException e) {
		}

		// invoke event callbacks
		for (LoggerEventListener callback : eventListeners) {
			try {
				callback.onStop(this);
			} catch (Exception e) {
				log.warn("logger callback should not throw any exception", e);
			}
		}
	}

	protected abstract void runOnce();

	protected void onStop() {
	}

	@Override
	public void run() {
		stopped = false;
		try {
			while (true) {
				try {
					if (doStop)
						break;
					long startedAt = System.currentTimeMillis();
					runOnce();
					updateConfig(config);
					long elapsed = System.currentTimeMillis() - startedAt;
					lastRunDate = new Date();
					if (interval - elapsed < 0)
						continue;
					Thread.sleep(interval - elapsed);
				} catch (InterruptedException e) {
				}
			}
		} catch (Exception e) {
			log.error("kraken log api: logger stopped", e);
		} finally {
			status = LoggerStatus.Stopped;
			stopped = true;
			doStop = false;

			try {
				onStop();
			} catch (Exception e) {
				log.warn("krane log api: [" + fullName + "] stop callback should not throw any exception", e);
			}
		}
	}

	protected void write(Log log) {
		// update last log date
		lastLogDate = log.getDate();
		logCounter.incrementAndGet();

		// notify all
		LogPipe[] capturedPipes = pipes;
		for (LogPipe pipe : capturedPipes) {
			try {
				pipe.onLog(this, log);
			} catch (Exception e) {
				if (e.getMessage() != null && e.getMessage().startsWith("invalid time"))
					this.log.warn("kraken-log-api: log pipe should not throw exception" + e.getMessage());
				else
					this.log.warn("kraken-log-api: log pipe should not throw exception", e);
			}
		}
	}

	@Override
	public void updateConfig(Properties config) {
		for (LoggerEventListener callback : eventListeners) {
			try {
				callback.onUpdated(this, config);
			} catch (Exception e) {
				log.error("kraken log api: logger event callback should not throw any exception", e);
			}
		}
	}

	@Override
	public Properties getConfig() {
		return config;
	}

	@Override
	public void addLogPipe(LogPipe pipe) {
		if (pipe == null)
			throw new IllegalArgumentException("pipe should be not null");

		// read-copy-update
		synchronized (updateLock) {
			// check if already exists
			boolean found = false;
			for (int i = 0; i < pipes.length; i++)
				if (pipes[i] == pipe)
					found = true;

			if (found)
				return;

			// copy old items
			LogPipe[] newPipes = new LogPipe[pipes.length + 1];
			for (int i = 0; i < pipes.length; i++)
				newPipes[i] = pipes[i];

			// add new item
			newPipes[pipes.length] = pipe;

			pipes = newPipes;
		}
	}

	@Override
	public void removeLogPipe(LogPipe pipe) {
		if (pipe == null)
			throw new IllegalArgumentException("pipe should be not null");

		// read-copy-update
		synchronized (updateLock) {
			// check if exists
			boolean found = false;
			for (int i = 0; i < pipes.length; i++)
				if (pipes[i] == pipe)
					found = true;

			if (!found)
				return;

			LogPipe[] newPipes = new LogPipe[pipes.length - 1];
			int j = 0;
			for (int i = 0; i < pipes.length; i++) {
				if (pipes[i] == pipe)
					continue;

				newPipes[j++] = pipes[i];
			}

			pipes = newPipes;
		}
	}

	@Override
	public void addEventListener(LoggerEventListener callback) {
		if (callback == null)
			throw new IllegalArgumentException("logger event listener must be not null");

		eventListeners.add(callback);
	}

	@Override
	public void removeEventListener(LoggerEventListener callback) {
		if (callback == null)
			throw new IllegalArgumentException("logger event listener must be not null");

		eventListeners.remove(callback);
	}

	@Override
	public void clearEventListeners() {
		eventListeners.clear();
	}

	@Override
	public String toString() {
		String a = toString(getLastLogDate());
		String b = toString(getLastRunDate());
		return String.format("name=%s, factory=%s, status=%s, log count=%d, last log=%s, last run=%s", getFullName(),
				factoryFullName, getStatus().toString().toLowerCase(), getLogCount(), a, b);
	}

	private String toString(Date d) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		if (d == null)
			return null;

		return dateFormat.format(d);
	}

}