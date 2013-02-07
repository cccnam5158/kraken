/*
 * Copyright 2013 Future Systems
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
package org.krakenapps.logdb.query.aggregator;

import java.util.List;

import org.krakenapps.logdb.LogQueryCommand.LogMap;
import org.krakenapps.logdb.query.ObjectComparator;
import org.krakenapps.logdb.query.expr.Expression;

public class Max implements AggregationFunction {
	private List<Expression> exprs;
	private ObjectComparator comp = new ObjectComparator();
	private Object max;

	public Max(List<Expression> exprs) {
		this.exprs = exprs;
	}

	@Override
	public String getName() {
		return "max";
	}

	@Override
	public List<Expression> getArguments() {
		return exprs;
	}

	@Override
	public void apply(LogMap map) {
		Object obj = exprs.get(0).eval(map);
		put(obj);
	}

	private void put(Object obj) {
		if (max == null || comp.compare(max, obj) < 0)
			max = obj;
	}

	public Object getMax() {
		return max;
	}

	public void setMax(Object max) {
		this.max = max;
	}

	@Override
	public Object eval() {
		return max;
	}

	@Override
	public void clean() {
		max = null;
	}

	@Override
	public AggregationFunction clone() {
		Max f = new Max(exprs);
		f.max = max;
		return f;
	}

	@Override
	public Object[] serialize() {
		Object[] l = new Object[1];
		l[0] = max;
		return l;
	}

	@Override
	public void deserialize(Object[] values) {
		this.max = values[0];
	}

	@Override
	public void merge(AggregationFunction func) {
		Max other = (Max) func;
		put(other.max);
	}

}
