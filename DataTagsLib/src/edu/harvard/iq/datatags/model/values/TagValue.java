package edu.harvard.iq.datatags.model.values;

import edu.harvard.iq.datatags.model.types.TagType;
import java.util.Objects;

/**
 *
 * @author michael
 * @param <T> The type class for value instances
 */
public abstract class TagValue<T extends TagType> {
	
	public interface Visitor<R> {
		R visitSimpleValue(SimpleValue v);
		R visitAggregateValue(AggregateValue v);
		R visitToDoValue(ToDoValue v);
	}

	public interface Function {
		public TagValue apply(TagValue v);
	}

	private final String name;
	private final T type;
	private final String info;

	public TagValue(String name, T type, String info) {
		this.name = name;
		this.type = type;
		this.info = info;
	}

	public String getName() {
		return name;
	}

	public T getType() {
		return type;
	}

	public String getInfo() {
		return info;
	}
	
	public abstract <R> R accept( TagValue.Visitor<R> visitor );
	
	/**
	 * Returns an instance that can take part in private copies of value 
	 * collections. In simple values, where all the data is immutable anyway,
	 * it just returns {@code this}. In aggregate values, where state is mutable,
	 * a new instance, created by deep-copying the state, is returned.
	 * 
	 * @return An instance that can be safely stored.
	 */
	public TagValue<T> getOwnableInstance() {
		return this;
	}
	
	@Override
	public int hashCode() {
		int hash = 7;
		hash = 37 * hash + Objects.hashCode(this.name);
		hash = 37 * hash + Objects.hashCode(this.type);
		return hash;
	}
	
	/**
	 * Base equality test - the type only.
	 * @param obj the other object
	 * @return can these objects be considered equal.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if ( !(obj instanceof TagValue) ) {
			return false;
		}
		final TagValue<?> other = (TagValue<?>) obj;
	
		return Objects.equals(this.type, other.type);
	}

	@Override
	public String toString() {
		return "[TagValue name:" + name + " type:" + type + ']';
	}
	
}