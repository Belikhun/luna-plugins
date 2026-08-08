package dev.belikhun.luna.core.api.model;

@FunctionalInterface
public interface ModelFactory<T extends Model<T>> {
	T create();
}

