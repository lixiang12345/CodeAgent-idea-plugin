import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class InspectPluginXml {
    private InspectPluginXml() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java scripts/InspectPluginXml.java PATH");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(Path.of(args[0]).toFile());

        NodeList actions = document.getElementsByTagName("action");
        for (int index = 0; index < actions.getLength(); index += 1) {
            Element action = (Element) actions.item(index);
            System.out.println("action\t" + action.getAttribute("id"));
        }

        Element extensions = (Element) document.getElementsByTagName("extensions").item(0);
        System.out.println("metric\textensions\t" + childElementCount(extensions));
        int listeners = document.getElementsByTagName("projectListeners").getLength() == 0
            ? 0
            : childElementCount((Element) document.getElementsByTagName("projectListeners").item(0));
        if (document.getElementsByTagName("applicationListeners").getLength() > 0) {
            listeners += childElementCount((Element) document.getElementsByTagName("applicationListeners").item(0));
        }
        System.out.println("metric\tlisteners\t" + listeners);
    }

    private static int childElementCount(Element parent) {
        int count = 0;
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index += 1) {
            if (children.item(index).getNodeType() == Node.ELEMENT_NODE) count += 1;
        }
        return count;
    }
}
