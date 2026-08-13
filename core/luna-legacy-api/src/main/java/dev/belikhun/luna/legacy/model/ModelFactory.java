package dev.belikhun.luna.legacy.model;

@FunctionalInterface
public interface ModelFactory<T extends Model<T>> {
	T create();
}

