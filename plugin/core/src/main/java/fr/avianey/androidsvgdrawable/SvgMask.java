/*
 * Copyright 2013, 2014, 2015 Antoine Vianey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.avianey.androidsvgdrawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import fr.avianey.androidsvgdrawable.Qualifier.Type;

/**
 * @author antoine vianey
 */
public class SvgMask {

	private static final Pattern REF_PATTERN = Pattern.compile("^#\\{(.*)\\}$");

	private final QualifiedResource resource;

	public SvgMask(final QualifiedResource resource) {
		this.resource = resource;
	}

	public QualifiedResource getResource() {
		return resource;
	}

	/**
	 * Generates masked SVG files for each matching combination of available SVG.
	 * @param dest
	 * @param availableResources
	 * @return
	 * @throws TransformerException
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws SAXException
	 * @throws XPathExpressionException
	 */
	public Collection<QualifiedResource> generatesMaskedResources(
	        File dest, final Collection<QualifiedResource> availableResources,
			final boolean useSameSvgOnlyOnceInMask,
			final OverrideMode overrideMode) throws TransformerException, ParserConfigurationException, SAXException, IOException, XPathExpressionException {
		// generates output directory
		dest.mkdirs();

		// parse mask
		DocumentBuilderFactory dfactory = DocumentBuilderFactory.newInstance();
		dfactory.setNamespaceAware(true);
		DocumentBuilder builder = dfactory.newDocumentBuilder();
		Document svgmaskDom = builder.parse(resource);
		final String svgNamespace = svgmaskDom.getDocumentElement().getNamespaceURI();

		// extract image node
        XPath xPath = XPathFactory.newInstance().newXPath();
        xPath.setNamespaceContext(new NamespaceContext() {
            public String getNamespaceURI(String prefix) {
                String uri = null;
                if (prefix.equals("_svgdrawable")) {
                    uri = svgNamespace;
                }
                return uri;
            }
            @Override
            public Iterator<?> getPrefixes(String val) {
                throw new IllegalAccessError("Not implemented!");
            }
            @Override
            public String getPrefix(String uri) {
                throw new IllegalAccessError("Not implemented!");
            }
        });

        // use dummy '_svgdrawable' prefix which is unlikely to be set for the svg namespace
        NodeList value = (NodeList) xPath.evaluate("//_svgdrawable:image", svgmaskDom, XPathConstants.NODESET);
		List<MaskNode> maskNodes = new ArrayList<>();
		for (int i = 0; i < value.getLength(); i++) {
			Node imageNode = value.item(i);
			Node href = imageNode.getAttributes().getNamedItemNS("http://www.w3.org/1999/xlink", "href");
			if (href != null && href.getNodeValue() != null) {
				Matcher m = REF_PATTERN.matcher(href.getNodeValue());
				if (m.matches()) {
					// this is a regexp to use for masking available resources
					MaskNode maskNode = new MaskNode(href, m.group(1));
					if (maskNode.accepts(availableResources)) {
						maskNodes.add(maskNode);
					} else {
						// skip mask
					}
				}
			}
		}

		final Collection<QualifiedResource> maskedResources = new ArrayList<>();

		if (!maskNodes.isEmpty()) {
			// cartesian product
			// init
			List<Iterator<QualifiedResource>> iterators = new ArrayList<>(maskNodes.size());
			List<QualifiedResource> currents = new ArrayList<>(maskNodes.size());
			for (MaskNode maskNode : maskNodes) {
				Iterator<QualifiedResource> i = maskNode.matchingResources.iterator();
				iterators.add(i);
				currents.add(i.next());
			}
			// each
			boolean hasNext = false;
			final Set<File> usedSvg = new HashSet<>();
			do {

				usedSvg.clear();
				usedSvg.addAll(currents);
				if (!useSameSvgOnlyOnceInMask || usedSvg.size() == currents.size()) {
					// we don't care about using the same svg twice or more
					// or the current combination contains distinct svg files only

					long lastModified = resource.lastModified();
					final StringBuilder tmpFileName = new StringBuilder(resource.getName());
					final EnumMap<Type, String> qualifiers = new EnumMap<>(Type.class);
					boolean skip = false;
					for (int i = 0; i < maskNodes.size(); i++) {
						// replace href attribute with svg file path
						QualifiedResource current = currents.get(i);
						MaskNode maskNode = maskNodes.get(i);
						maskNode.imageNode.setNodeValue("file:///" + current.getAbsolutePath());
						// concat name
						tmpFileName.append("_");
						tmpFileName.append(currents.get(i).getName());
						// concat qualifiers & verify compatibility
						// if a mask applies to two or more QualifiedResource with same Type but different values, the combination is skipped
						for (Entry<Type, String> e : qualifiers.entrySet()) {
							String qualifierValue = current.getTypedQualifiers().get(e.getKey());
							if (qualifierValue != null && !qualifierValue.equals(e.getValue())) {
								// skip the current combination
								skip = true;
								break;
							}
						}
						// union the qualifiers
						qualifiers.putAll(current.getTypedQualifiers());
						// update lastModified
						if (current.lastModified() > lastModified) {
							lastModified = current.lastModified();
						}
					}

					if (!skip) {
						// generates masked SVG for the current combination
						// - names against the mask name and the svg name
						// - combining qualifiers (union all except density)
						// - overrideMode support via override of lastModified() in QualifiedResource
						// - ninePatch support via regexp in ninePatchConfig
						qualifiers.remove(Type.density);
						qualifiers.put(Type.density, resource.getDensity().getValue().name());
						final String name = tmpFileName.toString();
						final File maskedPath = new File(dest, name + Qualifier.toQualifiedString(qualifiers) + ".svg");

						QualifiedResource maskedQualifiedResource = new MaskedQualifiedResource(maskedPath, name, lastModified, qualifiers);;

						// write masked svg
						if (overrideMode.shouldOverride(maskedQualifiedResource, maskedPath, null)) {
						    TransformerFactory transformerFactory = TransformerFactory.newInstance();
							Transformer transformer = transformerFactory.newTransformer();
							DOMSource source = new DOMSource(svgmaskDom);
							if (maskedQualifiedResource.exists() || maskedQualifiedResource.createNewFile()) {
								StreamResult result = new StreamResult(new FileOutputStream(maskedQualifiedResource));
								transformer.transform(source, result);
								maskedResources.add(maskedQualifiedResource);
							}
						} else {
						    // no need to re-generate masked file
						    // delegates override or not to final file generation process
                            maskedResources.add(maskedQualifiedResource);
						}

					}
				}

				// fill next combination
				hasNext = false;
				for (int i = maskNodes.size() - 1; i >= 0; i--) {
					if (iterators.get(i).hasNext()) {
						currents.set(i, iterators.get(i).next());
						hasNext = true;
						break;
					} else if (i > 0) {
						iterators.set(i, maskNodes.get(i).matchingResources.iterator());
						currents.set(i, iterators.get(i).next());
					}
				}

			} while (hasNext);
		}

		return maskedResources;
	}

	private class MaskNode {

		private final Node imageNode;
		private final String regexp;
		private final List<QualifiedResource> matchingResources;

		private MaskNode(Node imageNode, String regexp) {
			this.imageNode = imageNode;
			this.regexp = regexp;
			this.matchingResources = new ArrayList<>();
		}

		/**
		 * Find valid SVG resources to mask according to :
		 * <ul>
		 * <li>SVG {@link Qualifier} must contains all of the SVGMASK {@link Qualifier}</li>
		 * <li>SVN name must match the pattern of the &lt;image&gt; node "href" attribute</li>
		 * </ul>
		 * @param availableResources
		 * @return
		 */
		public boolean accepts(final Collection<QualifiedResource> availableResources) {
			final Set<Map.Entry<Type, String>> maskQualifiers = new HashSet<>(SvgMask.this.resource.getTypedQualifiers().entrySet());
			Set<Map.Entry<Type, String>> svgQualifiers = new HashSet<>();
			maskQualifiers.remove(new AbstractMap.SimpleEntry<>(Type.density, SvgMask.this.resource.getDensity().getValue().name()));
			for (QualifiedResource r : availableResources) {
				if (r.getName().matches(regexp)) {
				    svgQualifiers.clear();
					svgQualifiers.addAll(r.getTypedQualifiers().entrySet());
					svgQualifiers.remove(new AbstractMap.SimpleEntry<>(Type.density, r.getDensity().getValue().name()));
					if (maskQualifiers.containsAll(svgQualifiers)) {
						// the mask is valid for this svg
						matchingResources.add(r);
					}
				}
			}
			return !matchingResources.isEmpty();
		}

	}

	/**
	 * A {@link QualifiedResource} with a that have the {@link File#lastModified()} date of the most recent mask or masked resource.
	 *
	 * @author antoine vianey
	 */
	private static final class MaskedQualifiedResource extends QualifiedResource {

        private static final long serialVersionUID = 1L;

	    private final long lastModified;

	    private MaskedQualifiedResource(final File path, final String name, final long lastModified, final EnumMap<Type, String> qualifiers) {
	        super(path, name, qualifiers);
	        this.lastModified = lastModified;
	    }

        @Override
        public long lastModified() {
            return lastModified;
        }
    };

}
