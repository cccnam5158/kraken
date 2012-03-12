/*
 * Copyright 2011 Future Systems, Inc.
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
package org.krakenapps.confdb.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.krakenapps.api.PrimitiveParseCallback;
import org.krakenapps.codec.EncodingRule;
import org.krakenapps.confdb.Config;
import org.krakenapps.confdb.ConfigCollection;
import org.krakenapps.confdb.ConfigDatabase;
import org.krakenapps.confdb.ConfigIterator;
import org.krakenapps.confdb.ConfigParser;
import org.krakenapps.confdb.Predicate;

/**
 * Build collection snapshot based on collection log. Snapshot contains only doc
 * pointers, and read actual doc data when you call next()
 * 
 * @author xeraph
 * 
 */
class FileConfigIterator implements ConfigIterator {
	private ConfigDatabase db;
	private ConfigCollection col;
	private RevLogReader reader;
	private Iterator<RevLog> it;
	private Predicate pred;
	private Config prefetch;
	private boolean loaded;
	private boolean closed;
	private ConfigParser parser;

	public FileConfigIterator(ConfigDatabase db, ConfigCollection col, RevLogReader reader, List<RevLog> snapshot, Predicate pred) {
		this.db = db;
		this.col = col;
		this.reader = reader;
		this.it = snapshot.iterator();
		this.pred = pred;
	}

	@Override
	public boolean hasNext() {
		if (closed)
			return false;

		if (loaded)
			return prefetch != null;

		try {
			prefetch = getNextMatch();
		} catch (IOException e) {
			close();
			return false;
		}

		return prefetch != null;
	}

	@Override
	public Config next() {
		if (!it.hasNext() && !loaded)
			throw new NoSuchElementException("no more config item in collection");

		if (it.hasNext() && !loaded) {
			if (!hasNext()) {
				close();
				throw new NoSuchElementException("no more config item in collection");
			}
		}

		loaded = false;
		Config r = prefetch;
		prefetch = null;
		return r;
	}

	private Config getNextMatch() throws IOException {
		if (loaded)
			return prefetch;

		Config matched = null;
		while (it.hasNext()) {
			Config c = getNextConfig();
			if (pred == null || pred.eval(c)) {
				matched = c;
				break;
			}
		}

		loaded = (matched != null);

		if (loaded == false)
			close();

		return matched;
	}

	private Config getNextConfig() throws IOException {
		RevLog log = it.next();

		// fetch doc binary and decode it
		byte[] b = reader.readDoc(log.getDocOffset(), log.getDocLength());
		Object doc = EncodingRule.decode(ByteBuffer.wrap(b));
		return new FileConfig(db, col, log.getDocId(), log.getRev(), log.getPrevRev(), doc, parser);
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setParser(ConfigParser parser) {
		this.parser = parser;
	}

	@Override
	public Collection<Object> getDocuments() {
		try {
			Collection<Object> docs = new ArrayList<Object>();
			ConfigIterator it = this;
			while (it.hasNext())
				docs.add(it.next().getDocument());
			return docs;
		} finally {
			close();
		}
	}

	@Override
	public <T> Collection<T> getDocuments(Class<T> cls) {
		return getDocuments(cls, null);
	}

	@Override
	public <T> Collection<T> getDocuments(Class<T> cls, PrimitiveParseCallback callback) {
		try {
			Collection<T> docs = new ArrayList<T>();
			ConfigIterator it = this;
			while (it.hasNext())
				docs.add(it.next().getDocument(cls, callback));
			return docs;
		} finally {
			close();
		}
	}

	@Override
	public void close() {
		if (!closed) {
			reader.close();
			closed = true;
		}
	}
}