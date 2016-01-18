package com.dwl.dbtemplate.dbutils;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BeanProcessor {
	protected static final int PROPERTY_NOT_FOUND = -1;
	private static final Map<Class<?>, Object> primitiveDefaults = new HashMap();
	private final Map<String, String> columnToPropertyOverrides;

	static {
		primitiveDefaults.put(Integer.TYPE, Integer.valueOf(0));
		primitiveDefaults.put(Short.TYPE, Short.valueOf((short) 0));
		primitiveDefaults.put(Byte.TYPE, Byte.valueOf((byte) 0));
		primitiveDefaults.put(Float.TYPE, Float.valueOf(0.0F));
		primitiveDefaults.put(Double.TYPE, Double.valueOf(0.0D));
		primitiveDefaults.put(Long.TYPE, Long.valueOf(0L));
		primitiveDefaults.put(Boolean.TYPE, Boolean.FALSE);
		primitiveDefaults.put(Character.TYPE, Character.valueOf('\000'));
	}

	public BeanProcessor() {
		this(new HashMap());
	}

	public BeanProcessor(Map<String, String> columnToPropertyOverrides) {
		if (columnToPropertyOverrides == null) {
			throw new IllegalArgumentException("columnToPropertyOverrides map cannot be null");
		}
		this.columnToPropertyOverrides = columnToPropertyOverrides;
	}

	public <T> T toBean(ResultSet rs, Class<T> type) throws SQLException {
		PropertyDescriptor[] props = propertyDescriptors(type);

		ResultSetMetaData rsmd = rs.getMetaData();
		int[] columnToProperty = mapColumnsToProperties(rsmd, props);

		return createBean(rs, type, props, columnToProperty);
	}

	public <T> List<T> toBeanList(ResultSet rs, Class<T> type) throws SQLException {
		List results = new ArrayList();

		if (!rs.next()) {
			return results;
		}

		PropertyDescriptor[] props = propertyDescriptors(type);
		ResultSetMetaData rsmd = rs.getMetaData();
		int[] columnToProperty = mapColumnsToProperties(rsmd, props);
		do {
			results.add(createBean(rs, type, props, columnToProperty));
		} while (rs.next());

		return results;
	}

	private <T> T createBean(ResultSet rs, Class<T> type, PropertyDescriptor[] props, int[] columnToProperty)
			throws SQLException {
		Object bean = newInstance(type);

		for (int i = 1; i < columnToProperty.length; i++) {
			if (columnToProperty[i] == -1) {
				continue;
			}
			PropertyDescriptor prop = props[columnToProperty[i]];
			Class propType = prop.getPropertyType();

			Object value = processColumn(rs, i, propType);

			if ((propType != null) && (value == null) && (propType.isPrimitive())) {
				value = primitiveDefaults.get(propType);
			}

			callSetter(bean, prop, value);
		}

		return (T) bean;
	}

	private void callSetter(Object target, PropertyDescriptor prop, Object value) throws SQLException {
		Method setter = prop.getWriteMethod();

		if (setter == null) {
			return;
		}

		Class[] params = setter.getParameterTypes();
		try {
			if ((value instanceof java.util.Date)) {
				String targetType = params[0].getName();
				if ("java.sql.Date".equals(targetType)) {
					value = new java.sql.Date(((java.util.Date) value).getTime());
				} else if ("java.sql.Time".equals(targetType)) {
					value = new Time(((java.util.Date) value).getTime());
				} else if ("java.sql.Timestamp".equals(targetType)) {
					value = new Timestamp(((java.util.Date) value).getTime());
				}

			}

			if (isCompatibleType(value, params[0]))
				setter.invoke(target, new Object[] { value });
			else {
				throw new SQLException("Cannot set " + prop.getName() + ": incompatible types, cannot convert "
						+ value.getClass().getName() + " to " + params[0].getName());
			}
		} catch (IllegalArgumentException e) {
			throw new SQLException("Cannot set " + prop.getName() + ": " + e.getMessage());
		} catch (IllegalAccessException e) {
			throw new SQLException("Cannot set " + prop.getName() + ": " + e.getMessage());
		} catch (InvocationTargetException e) {
			throw new SQLException("Cannot set " + prop.getName() + ": " + e.getMessage());
		}
	}

	private boolean isCompatibleType(Object value, Class<?> type) {
		if ((value == null) || (type.isInstance(value))) {
			return true;
		}
		if ((type.equals(Integer.TYPE)) && (Integer.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Long.TYPE)) && (Long.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Double.TYPE)) && (Double.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Float.TYPE)) && (Float.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Short.TYPE)) && (Short.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Byte.TYPE)) && (Byte.class.isInstance(value))) {
			return true;
		}
		if ((type.equals(Character.TYPE)) && (Character.class.isInstance(value))) {
			return true;
		}

		return (type.equals(Boolean.TYPE)) && (Boolean.class.isInstance(value));
	}

	protected <T> T newInstance(Class<T> c) throws SQLException {
		try {
			return c.newInstance();
		} catch (InstantiationException e) {
			throw new SQLException("Cannot create " + c.getName() + ": " + e.getMessage());
		} catch (IllegalAccessException e) {
			throw new SQLException("Cannot create " + c.getName() + ": " + e.getMessage());
		}
	}

	private PropertyDescriptor[] propertyDescriptors(Class<?> c) throws SQLException {
		BeanInfo beanInfo = null;
		try {
			beanInfo = Introspector.getBeanInfo(c);
		} catch (IntrospectionException e) {
			throw new SQLException("Bean introspection failed: " + e.getMessage());
		}

		return beanInfo.getPropertyDescriptors();
	}

	protected int[] mapColumnsToProperties(ResultSetMetaData rsmd, PropertyDescriptor[] props) throws SQLException {
		int cols = rsmd.getColumnCount();
		int[] columnToProperty = new int[cols + 1];
		Arrays.fill(columnToProperty, -1);

		for (int col = 1; col <= cols; col++) {
			String columnName = rsmd.getColumnLabel(col);
			if ((columnName == null) || (columnName.length() == 0)) {
				columnName = rsmd.getColumnName(col);
			}
			String propertyName = (String) this.columnToPropertyOverrides.get(columnName);
			if (propertyName == null) {
				propertyName = columnName;
			}
			for (int i = 0; i < props.length; i++) {
				if (propertyName.equalsIgnoreCase(props[i].getName())) {
					columnToProperty[col] = i;
					break;
				}
			}
		}

		return columnToProperty;
	}

	protected Object processColumn(ResultSet rs, int index, Class<?> propType) throws SQLException {
		if ((!propType.isPrimitive()) && (rs.getObject(index) == null)) {
			return null;
		}

		if (propType.equals(String.class)) {
			return rs.getString(index);
		}

		if ((propType.equals(Integer.TYPE)) || (propType.equals(Integer.class))) {
			return Integer.valueOf(rs.getInt(index));
		}

		if ((propType.equals(Boolean.TYPE)) || (propType.equals(Boolean.class))) {
			return Boolean.valueOf(rs.getBoolean(index));
		}
		if ((propType.equals(Long.TYPE)) || (propType.equals(Long.class))) {
			return Long.valueOf(rs.getLong(index));
		}

		if ((propType.equals(Double.TYPE)) || (propType.equals(Double.class))) {
			return Double.valueOf(rs.getDouble(index));
		}

		if ((propType.equals(Float.TYPE)) || (propType.equals(Float.class))) {
			return Float.valueOf(rs.getFloat(index));
		}

		if ((propType.equals(Short.TYPE)) || (propType.equals(Short.class))) {
			return Short.valueOf(rs.getShort(index));
		}
		if ((propType.equals(Byte.TYPE)) || (propType.equals(Byte.class))) {
			return Byte.valueOf(rs.getByte(index));
		}
		if (propType.equals(Timestamp.class)) {
			return rs.getTimestamp(index);
		}
		if (propType.equals(SQLXML.class)) {
			return rs.getSQLXML(index);
		}

		return rs.getObject(index);
	}
}
