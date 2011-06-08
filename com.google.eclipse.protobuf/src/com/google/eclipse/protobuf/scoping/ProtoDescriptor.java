/*
 * Copyright (c) 2011 Google Inc.
 *
 * All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License v1.0 which accompanies this distribution, and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.google.eclipse.protobuf.scoping;

import static com.google.eclipse.protobuf.protobuf.ProtobufPackage.Literals.PROPERTY__TYPE;
import static com.google.eclipse.protobuf.scoping.OptionType.*;
import static com.google.eclipse.protobuf.util.Closeables.close;
import static java.util.Collections.unmodifiableCollection;
import static org.eclipse.xtext.EcoreUtil2.*;
import static org.eclipse.xtext.util.CancelIndicator.NullImpl;
import static org.eclipse.xtext.util.Strings.isEmpty;

import com.google.eclipse.protobuf.protobuf.*;
import com.google.eclipse.protobuf.protobuf.Enum;
import com.google.eclipse.protobuf.util.ModelNodes;
import com.google.inject.Inject;

import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.parser.*;
import org.eclipse.xtext.resource.XtextResource;

import java.io.InputStreamReader;
import java.util.*;

/**
 * Contains the elements from descriptor.proto (provided with protobuf's library.)
 *
 * @author alruiz@google.com (Alex Ruiz)
 */
public class ProtoDescriptor {

  private static final Map<String, OptionType> OPTION_DEFINITION_BY_NAME = new HashMap<String, OptionType>();
  
  static {
    OPTION_DEFINITION_BY_NAME.put("FileOptions", FILE);
    OPTION_DEFINITION_BY_NAME.put("MessageOptions", MESSAGE);
    OPTION_DEFINITION_BY_NAME.put("FieldOptions", FIELD);
    OPTION_DEFINITION_BY_NAME.put("MethodOptions", METHOD);
  }
  
  private final Map<OptionType, Map<String, Property>> optionsByType = new HashMap<OptionType, Map<String, Property>>();
  private final Map<String, Enum> enumsByName = new HashMap<String, Enum>();

  private Protobuf root;

  private final ModelNodes nodes;

  @Inject public ProtoDescriptor(IParser parser, IProtoDescriptorSource source, ModelNodes nodes) {
    this.nodes = nodes;
    addOptionTypes();
    InputStreamReader reader = null;
    try {
      XtextResource resource = new XtextResource(source.uri());
      reader = new InputStreamReader(source.contents(), "UTF-8");
      IParseResult result = parser.parse(reader);
      root = (Protobuf) result.getRootASTElement();
      resource.getContents().add(root);
      resolveLazyCrossReferences(resource, NullImpl);
      initContents();
    } catch (Throwable t) {
      t.printStackTrace();
      throw new IllegalStateException("Unable to parse descriptor.proto", t);
    } finally {
      close(reader);
    }
  }

  private void addOptionTypes() {
    for (OptionType type : OptionType.values())
      optionsByType.put(type, new LinkedHashMap<String, Property>());
  }

  private void initContents() {
    for (Message m : getAllContentsOfType(root, Message.class)) {
      OptionType type = OPTION_DEFINITION_BY_NAME.get(m.getName());
      if (type == null) continue;
      initOptions(m, type);
    }
  }

  private void initOptions(Message optionGroup, OptionType type) {
    for (MessageElement e : optionGroup.getElements()) {
      if (e instanceof Property) {
        addOption((Property) e, type);
        continue;
      }
      if (e instanceof Enum) {
        Enum anEnum = (Enum) e;
        enumsByName.put(anEnum.getName(), anEnum);
      }
    }
  }

  private void addOption(Property optionDefinition, OptionType type) {
    if (shouldIgnore(optionDefinition)) return;
    optionsByType.get(type).put(optionDefinition.getName(), optionDefinition);
  }

  private boolean shouldIgnore(Property property) {
    return "uninterpreted_option".equals(property.getName());
  }

  /**
   * Returns all the file-level options available. These are the options defined in
   * {@code google/protobuf/descriptor.proto} (more details can be found
   * <a href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @return all the file-level options available.
   */
  public Collection<Property> fileOptions() {
    return optionsOfType(FILE);
  }

  /**
   * Looks up an option per name, as defined in {@code google/protobuf/descriptor.proto}
   * (more details can be found <a
   * href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @param name the name of the option to look for.
   * @return the option whose name matches the given one or {@code null} if a matching option is not found.
   */
  public Property lookupOption(String name) {
    return lookupOption(name, FILE, MESSAGE, METHOD);
  }
  
  private Property lookupOption(String name, OptionType...types) {
    for (OptionType type : types) {
      Property p = lookupOption(name, type);
      if (p != null) return p;
    }
    return null;
  }

  /**
   * Looks up a field-level option per name. Field-level options are defined in {@code google/protobuf/descriptor.proto}
   * (more details can be found <a
   * href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @param name the name of the option to look for.
   * @return the option whose name matches the given one or {@code null} if a matching option is not found.
   */
  public Property lookupFieldOption(String name) {
    return lookupOption(name, FIELD);
  }

  private Property lookupOption(String name, OptionType type) {
    return optionsByType.get(type).get(name);
  }

  /**
   * Returns all the message-level options available. These are the options defined in
   * {@code google/protobuf/descriptor.proto} (more details can be found
   * <a href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @return all the message-level options available.
   */
  public Collection<Property> messageOptions() {
    return optionsOfType(MESSAGE);
  }

  /**
   * Returns all the field-level options available. These are the options defined in
   * {@code google/protobuf/descriptor.proto} (more details can be found
   * <a href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @return all the field-level options available.
   */
  public Collection<Property> fieldOptions() {
    return optionsOfType(FIELD);
  }

  private Collection<Property> optionsOfType(OptionType type) {
    return unmodifiableCollection(optionsByType.get(type).values());
  }

  /**
   * Returns the enum type of the given option, only if the given option is defined in
   * {@code google/protobuf/descriptor.proto} and its type is enum (more details can be found <a
   * href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @param option the given option.
   * @return the enum type of the given option or {@code null} if the type of the given option is not enum.
   */
  public Enum enumTypeOf(Option option) {
    String name = option.getName();
    return enumTypeOf(lookupOption(name));
  }

  /**
   * Returns the enum type of the given option, only if the given option is defined in
   * {@code google/protobuf/descriptor.proto} and its type is enum (more details can be found <a
   * href=http://code.google.com/apis/protocolbuffers/docs/proto.html#options" target="_blank">here</a>.)
   * @param option the given option.
   * @return the enum type of the given option or {@code null} if the type of the given option is not enum.
   */
  public Enum enumTypeOf(FieldOption option) {
    String name = option.getName();
    return enumTypeOf(lookupFieldOption(name));
  }

  private Enum enumTypeOf(Property p) {
    if (p == null) return null;
    INode node = nodes.firstNodeForFeature(p, PROPERTY__TYPE);
    if (node == null) return null;
    String typeName = node.getText();
    return (isEmpty(typeName)) ? null : enumsByName.get(typeName.trim());
  }
}