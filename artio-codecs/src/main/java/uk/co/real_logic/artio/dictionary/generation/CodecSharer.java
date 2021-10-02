/*
 * Copyright 2021 Adaptive Financial Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.dictionary.generation;

import uk.co.real_logic.artio.dictionary.DictionaryParser;
import uk.co.real_logic.artio.dictionary.ir.Dictionary;
import uk.co.real_logic.artio.dictionary.ir.*;
import uk.co.real_logic.artio.dictionary.ir.Field.Value;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;
import static java.util.stream.Stream.concat;

class CodecSharer
{
    // Used in sharedNameToField in order to denote a name that has a clash
    private static final Field CLASH_SENTINEL = new Field(-1, "SHARING_CLASH", Field.Type.INT);

    private final List<Dictionary> inputDictionaries;

    private final Map<String, Field> sharedNameToField = new HashMap<>();
    private final Map<String, Group> sharedIdToGroup = new HashMap<>();
    private final Set<String> commonGroupIds = new HashSet<>();

    CodecSharer(final List<Dictionary> inputDictionaries)
    {
        this.inputDictionaries = inputDictionaries;
    }

    public void share()
    {
        findSharedFields();

//        System.out.println("sharedNameToField = " + sharedNameToField);

        findSharedGroups();
        final List<Message> messages = findSharedMessages();
        final Map<String, Component> components = findSharedComponents();
        final Component header = findSharedComponent(Dictionary::header);
        final Component trailer = findSharedComponent(Dictionary::trailer);
        final String specType = DictionaryParser.DEFAULT_SPEC_TYPE;
        final int majorVersion = 0;
        final int minorVersion = 0;

//        System.out.println("commonGroupIds = " + commonGroupIds);
//        System.out.println("sharedIdToGroup = " + sharedIdToGroup);
//        System.out.println("components = " + components);
//        System.out.println("inputDictionaries.get(0).components() = " + inputDictionaries.get(0).components());
//        System.out.println("messages = " + messages);

        final Dictionary sharedDictionary = new Dictionary(
            messages,
            sharedNameToField,
            components,
            header,
            trailer,
            specType,
            majorVersion,
            minorVersion);

        sharedDictionary.shared(true);
        inputDictionaries.forEach(dict -> connectToSharedDictionary(sharedDictionary, dict));
        inputDictionaries.add(sharedDictionary);
    }

    private void findSharedGroups()
    {
        findSharedAggregates(
            sharedIdToGroup,
            commonGroupIds,
            dict -> concat(grpPairs(dict.messages()), grpPairs(dict.components().values())).collect(toList()),
            (comp, sharedGroup) -> true,
            this::copyOf,
            this::sharedGroupId);
    }

    interface GetName<T extends Aggregate> extends Function<AggPath<T>, String>
    {
    }

    interface CopyOf<T extends Aggregate> extends BiFunction<List<Aggregate>, T, T>
    {
    }

    private String sharedGroupId(final AggPath<Group> pair)
    {
        return pair.parents().stream().map(Aggregate::name).collect(joining(".")) + "." + pair.agg().name();
    }

    private Stream<AggPath<Group>> grpPairs(final Collection<? extends Aggregate> aggregates)
    {
        return aggregates.stream().flatMap(agg -> grpPairs(agg, Collections.emptyList()));
    }

    // Groups within this aggregate and groups within groups, but not groups within components within this aggregate
    public Stream<AggPath<Group>> grpPairs(final Aggregate aggregate, final List<Aggregate> prefix)
    {
        final List<Aggregate> parents = path(prefix, aggregate);

        return aggregate.entriesWith(ele -> ele instanceof Group)
            .flatMap(e ->
            {
                final Group group = (Group)e.element();
                final Stream<AggPath<Group>> pair = Stream.of(new AggPath<>(parents, group));
                return concat(pair, grpPairs(group, parents));
            });
    }

    private List<Aggregate> path(final List<Aggregate> prefix, final Aggregate aggregate)
    {
        final List<Aggregate> parents = new ArrayList<>(prefix);
        parents.add(aggregate);
        return parents;
    }

    private Map<String, Component> findSharedComponents()
    {
        return findSharedAggregates(
            new HashMap<>(),
            null,
            dict -> addFakeParents(dict.components().values()),
            (comp, sharedComp) -> true,
            this::copyOf,
            pair -> pair.agg().name());
    }

    private List<Message> findSharedMessages()
    {
        final Map<String, Message> nameToMessage = findSharedAggregates(
            new HashMap<>(),
            null,
            dict -> addFakeParents(dict.messages()),
            (msg, sharedMessage) -> msg.packedType() == sharedMessage.packedType(),
            this::copyOf,
            pair -> pair.agg().name());

        return new ArrayList<>(nameToMessage.values());
    }

    private static final class AggPath<T extends Aggregate>
    {
        private final List<Aggregate> parents;
        private final T agg;

        private AggPath(final List<Aggregate> parents, final T agg)
        {
            this.parents = parents;
            this.agg = agg;
        }

        public List<Aggregate> parents()
        {
            return parents;
        }

        public T agg()
        {
            return agg;
        }
    }

    private <T extends Aggregate> Collection<AggPath<T>> addFakeParents(final Collection<T> aggregates)
    {
        return aggregates
            .stream()
            .map(agg -> new AggPath<T>(Collections.emptyList(), agg))
            .collect(toList());
    }

    // Groups existing within a Message or Component, so we put the message or component name into the
    // name map for the groups, so we can lookup the precise group later on.
    // Thus all the getName functions that need parent objects
    private <T extends Aggregate> Map<String, T> findSharedAggregates(
        final Map<String, T> nameToAggregate,
        final Set<String> commonAggregateNamesCopy,
        final Function<Dictionary, Collection<AggPath<T>>> dictToAggParentPair,
        final BiPredicate<T, T> check,
        final CopyOf<T> copyOf,
        final GetName<T> getName)
    {
        final Set<String> commonAggregateNames = findCommonNames(dict ->
            aggregateNames(dictToAggParentPair.apply(dict), getName));
        if (commonAggregateNamesCopy != null)
        {
            commonAggregateNamesCopy.addAll(commonAggregateNames);
        }
        for (final Dictionary dictionary : inputDictionaries)
        {
            dictToAggParentPair.apply(dictionary).forEach(e ->
            {
                final T agg = e.agg();
                final String name = getName.apply(e);
                if (commonAggregateNames.contains(name))
                {
                    final T sharedAggregate = nameToAggregate.get(name);
                    if (sharedAggregate == null)
                    {
                        nameToAggregate.put(name, copyOf.apply(e.parents(), agg));
                        agg.isInParent(true);
                    }
                    else
                    {
                        // merge fields within aggregate
                        if (!check.test(agg, sharedAggregate))
                        {
                            // TODO: if it happens then push the type down into the implementations
                            System.err.println("Invalid types: ");
                            System.err.println(agg);
                            System.err.println(sharedAggregate);
                        }
                        else
                        {
                            // Still need to merge aggregates even though we have merged fields
                            // As some fields may be common to different dictionaries but not messages
                            mergeAggregate(agg, sharedAggregate);
                        }
                    }
                }
            });
        }

        identifyAggregateEntriesInParent(nameToAggregate, dictToAggParentPair, getName);

        return nameToAggregate;
    }

    private <T extends Aggregate> void identifyAggregateEntriesInParent(
        final Map<String, T> nameToAggregate,
        final Function<Dictionary, Collection<AggPath<T>>> dictToAggParentPair,
        final GetName<T> getName)
    {
        inputDictionaries.forEach(dictionary ->
        {
            dictToAggParentPair.apply(dictionary).forEach(e ->
            {
                final T agg = e.agg();
                final String name = getName.apply(e);
                final Aggregate sharedAggregate = nameToAggregate.get(name);
                if (sharedAggregate != null)
                {
                    identifyAggregateEntriesInParent(agg, sharedAggregate);
                }
            });
        });
    }

    private void identifyAggregateEntriesInParent(final Aggregate agg, final Aggregate sharedAgg)
    {
        sharedAgg.entries().forEach(sharedEntry ->
        {
            agg.entries().forEach(entry ->
            {
                if (entry.name().equals(sharedEntry.name()))
                {
                    entry.isInParent(true);
                }
            });
        });
    }

    private void connectToSharedDictionary(final Dictionary sharedDictionary, final Dictionary dict)
    {
        dict.sharedParent(sharedDictionary);
    }

    private void findSharedFields()
    {
        final Set<String> commonNonEnumFieldNames = findCommonNonEnumFieldNames();
        final Set<String> allEnumFieldNames = allEnumFieldNames();

        for (final Dictionary dictionary : inputDictionaries)
        {
            final Map<String, Field> fields = dictionary.fields();
            commonNonEnumFieldNames.forEach(fieldName -> mergeField(fields, fieldName));
            allEnumFieldNames.forEach(enumName -> mergeField(fields, enumName));
        }

        formUnionEnums();

        sharedNameToField.values().forEach(field ->
            DictionaryParser.checkAssociatedLengthField(sharedNameToField, field, "CodecSharer"));
        sharedNameToField.values().removeIf(field -> field == CLASH_SENTINEL);
    }

    private void formUnionEnums()
    {
        sharedNameToField.values().forEach(field ->
        {
            if (field.isEnum())
            {
                final List<Value> fieldValues = field.values();

                // Rename collisions by name
                final Map<String, Map<String, Long>> nameToReprToCount = fieldValues.stream().collect(
                    groupingBy(Value::description,
                    groupingBy(Value::representation, Collectors.counting())));

                // Make a new unique Value for every input with the name collisions fixed
                final List<Value> values = nameToReprToCount
                    .entrySet().stream().flatMap(e ->
                    {
                        final String name = e.getKey();
                        final Map<String, Long> reprToCount = e.getValue();
                        final String commonRepr = findCommonName(reprToCount);
                        final Stream<Value> commonValues =
                            LongStream.range(0, reprToCount.get(commonRepr)).mapToObj(i ->
                            new Value(commonRepr, name));

                        final HashSet<String> otherReprs = new HashSet<>(reprToCount.keySet());
                        otherReprs.remove(commonRepr);

                        final Stream<Value> otherValues = otherReprs.stream().flatMap(repr ->
                        {
                            final String newName = name + "_" + repr;
                            return LongStream.range(0, reprToCount.get(repr))
                                .mapToObj(i -> new Value(repr, newName));
                        });

                        return concat(commonValues, otherValues);
                    })
                    .collect(toList());

                // Add name collisions by representation to Javadoc
                final Map<String, Map<String, Long>> reprToNameToCount = values.stream().collect(
                    groupingBy(Value::representation,
                    groupingBy(Value::description, Collectors.counting())));

                final List<Value> finalValues = reprToNameToCount
                    .entrySet().stream().map(e ->
                    {
                        final String repr = e.getKey();
                        final Map<String, Long> nameToCount = e.getValue();
                        final String commonName = findCommonName(nameToCount);
                        final Value value = new Value(repr, commonName);
                        if (nameToCount.size() > 1)
                        {
                            final Set<String> otherNames = nameToCount.keySet();
                            otherNames.remove(commonName);
                            value.alternativeNames(new ArrayList<>(otherNames));
                        }
                        return value;
                    })
                    .collect(toList());

                fieldValues.clear();
                fieldValues.addAll(finalValues);
            }
        });
    }

    private String findCommonName(final Map<String, Long> nameToCount)
    {
        return nameToCount.keySet().stream().max(Comparator.comparingLong(name -> nameToCount.get(name))).get();
    }

    private void mergeField(final Map<String, Field> fields, final String fieldName)
    {
        sharedNameToField.compute(fieldName, (name, sharedField) ->
        {
            final Field field = fields.get(name);
            if (field == null)
            {
                return sharedField;
            }

            if (sharedField == null)
            {
                return copyOf(field);
            }
            else
            {
                final Field.Type sharedType = sharedField.type();
                final Field.Type type = field.type();
                if (sharedType != type && BaseType.from(sharedType) != BaseType.from(type))
                {
                    return CLASH_SENTINEL;
                }

                mergeEnumValues(sharedField, field);

                return sharedField;
            }
        });
    }

    private void mergeEnumValues(final Field sharedField, final Field field)
    {
        final List<Value> sharedValues = sharedField.values();
        final List<Value> values = field.values();

        final boolean sharedIsEnum = sharedValues.isEmpty();
        final boolean isEnum = values.isEmpty();

        if (sharedIsEnum != isEnum)
        {
            sharedField.hasSharedSometimesEnumClash(true);
        }

        sharedValues.addAll(values);
    }

    private Set<String> allEnumFieldNames()
    {
        return inputDictionaries
            .stream()
            .flatMap(dict -> fieldNames(dict, true))
            .collect(Collectors.toSet());
    }

    private Field copyOf(final Field field)
    {
        final Field newField = new Field(field.number(), field.name(), field.type());
        newField.values().addAll(field.values());
        return newField;
    }

    private Component findSharedComponent(final Function<Dictionary, Component> getter)
    {
        final Dictionary firstDictionary = inputDictionaries.get(0);
        final Component sharedComponent = copyOf(Collections.emptyList(), getter.apply(firstDictionary));
        inputDictionaries.forEach(dict ->
        {
            final Component component = getter.apply(dict);
            mergeAggregate(component, sharedComponent);
        });

        inputDictionaries.forEach(dict ->
        {
            final Component component = getter.apply(dict);
            identifyAggregateEntriesInParent(component, sharedComponent);
        });

        return sharedComponent;
    }

    private void mergeAggregate(final Aggregate aggregate, final Aggregate sharedAggregate)
    {
        aggregate.isInParent(true);

        final Map<String, Entry> nameToEntry = nameToEntry(aggregate.entries());
        final Iterator<Entry> it = sharedAggregate.entries().iterator();
        while (it.hasNext())
        {
            final Entry sharedEntry = it.next();
            final Entry entry = nameToEntry.get(sharedEntry.name());
            if (entry == null)
            {
                it.remove();
            }
            else
            {
                // Only required if all are required
                sharedEntry.required(sharedEntry.required() && entry.required());
            }
        }
    }

    private Set<String> findCommonNonEnumFieldNames()
    {
        return findCommonNames(dictionary -> fieldNames(dictionary, false).collect(Collectors.toSet()));
    }

    private Set<String> findCommonNames(final Function<Dictionary, Set<String>> getAllNames)
    {
        final Set<String> messageNames = new HashSet<>();
        inputDictionaries.forEach(dict ->
        {
            final Set<String> namesInDictionary = getAllNames.apply(dict);
            if (messageNames.isEmpty())
            {
                messageNames.addAll(namesInDictionary);
            }
            else
            {
                messageNames.retainAll(namesInDictionary);
            }
        });

        return messageNames;
    }

    private <T extends Aggregate> Set<String> aggregateNames(
        final Collection<AggPath<T>> aggregates,
        final GetName<T> getName)
    {
        return aggregates.stream().map(getName).collect(Collectors.toSet());
    }

    private Stream<String> fieldNames(final Dictionary dictionary, final boolean isEnum)
    {
        return dictionary.fields().entrySet()
            .stream()
            .filter(e -> e.getValue().isEnum() == isEnum)
            .map(Map.Entry::getKey);
    }

    private Map<String, Entry> nameToEntry(final List<Entry> entries)
    {
        return entries.stream().collect(Collectors.toMap(Entry::name, x -> x));
    }

    // pre: shared fields calculated
    private Entry copyOf(final List<Aggregate> parents, final Entry entry)
    {
        Entry.Element element = entry.element();

        // Remap fields to calculated shared values
        if (element instanceof Field)
        {
            final Field field = (Field)element;
            final String name = field.name();
            element = sharedNameToField.get(name);
            if (element == null)
            {
                return null;
            }
        }
        else if (element instanceof Component)
        {
            element = copyOf(parents, (Component)element);
        }
        else if (element instanceof Group)
        {
            final Group group = (Group)element;
            final String id = sharedGroupId(new AggPath<>(parents, group));
            // Nested Groups need to update this shared id map in order to copy the outer element of a nested group
            Group sharedGroup = sharedIdToGroup.get(id);
            if (sharedGroup == null)
            {
                if (!commonGroupIds.contains(id))
                {
                    // Just remove the inner nested group as it isn't in the shared dictionary anyway
                    return null;
                }

                sharedGroup = copyOf(parents, group);
                sharedIdToGroup.put(id, sharedGroup);
            }

            element = sharedGroup;
        }
        else
        {
            throw new IllegalArgumentException("Unknown element type: " + element);
        }

        return new Entry(entry.required(), element);
    }

    private Component copyOf(final List<Aggregate> prefix, final Component component)
    {
        final Component newComponent = new Component(component.name());
        copyTo(prefix, component, newComponent);
        return newComponent;
    }

    private Group copyOf(final List<Aggregate> prefix, final Group group)
    {
        final List<Aggregate> parents = path(prefix, group);

        final Entry numberField = group.numberField();
        final Entry copiedNumberField = copyOf(parents, numberField);
        final Group newGroup = new Group(group.name(), copiedNumberField);
        copyTo(prefix, group, newGroup);
        return newGroup;
    }

    private Message copyOf(final List<Aggregate> prefix, final Message message)
    {
        final Message newMessage = new Message(message.name(), message.fullType(), message.category());
        copyTo(prefix, message, newMessage);
        return newMessage;
    }

    private void copyTo(final List<Aggregate> prefix, final Aggregate aggregate, final Aggregate newAggregate)
    {
        final List<Aggregate> parents = path(prefix, aggregate);

        for (final Entry entry : aggregate.entries())
        {
            final Entry newEntry = copyOf(parents, entry);
            if (newEntry != null)
            {
                newAggregate.entries().add(newEntry);
            }
        }
    }

}
