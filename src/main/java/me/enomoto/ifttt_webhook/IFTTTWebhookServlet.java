package me.enomoto.ifttt_webhook;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

@WebServlet(name = "IFTTTWebhookServlet", urlPatterns = "/*")
public class IFTTTWebhookServlet extends HttpServlet {

    private static final long serialVersionUID = 1583608008138829380L;

    private static Logger LOGGER = LoggerFactory.getLogger(IFTTTWebhookServlet.class);

    // @value@
    protected static final String OK = "<?xml version=\"1.0\"?><methodResponse><params><param><value>@value@</value></param></params></methodResponse>";

    // @faultCode@
    protected static final String FAULT = "<?xml version=\"1.0\"?><methodResponse><fault><value><struct><member><name>faultCode</name><value><int>@faultCode@</int></value></member><member><name>faultString</name><value><string>Request was not successful.</string></value></member></struct></value></fault></methodResponse>";

    private static DocumentBuilderFactory F = DocumentBuilderFactory.newInstance();

    private static XPathExpression METHOD_NAME;

    private static XPathExpression PARAM_VALUES;

    private static XPathExpression PARAM_MEMBERS;

    private static XPathExpression MEMBER_NAME;

    private static XPathExpression MEMBER_VALUE;

    private static XPathExpression MEMBER_ARRAY_VALUES;

    static {
        final XPathFactory f = XPathFactory.newInstance();
        final XPath xpath = f.newXPath();
        try {
            METHOD_NAME = xpath.compile("//methodName/text()");
            PARAM_VALUES = xpath.compile("//params/param/value/string/text()");
            PARAM_MEMBERS = xpath.compile("//params/param/value/struct/member");
            MEMBER_NAME = xpath.compile("./name/text()");
            MEMBER_VALUE = xpath.compile("./value/string/text()");
            MEMBER_ARRAY_VALUES = xpath.compile("./value/array/data/value/string/text()");
        } catch (final XPathExpressionException e) {
            throw new InternalError(e.getLocalizedMessage());
        }
    }

    protected void process(final WordpressPostRequest wpRequest) throws IOException {
        System.out.println(wpRequest);
    }

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        doPost(request, response);
    }

    @Override
    protected void doPost(final HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException {
        WordpressPostRequest wpRequest = null;
        try (InputStream in = request.getInputStream()) {
            wpRequest = parse(in);
        } catch (final IOException e) {
            fail(request, response, e);
            return;
        }

        response.setContentType("text/xml");
        response.setStatus(HttpServletResponse.SC_OK);
        switch (wpRequest.methodName) {
        case "mt.supportedMethods":
            success(request, response, "<string>metaWeblog.getRecentPosts</string>");
            break;
        case "wp.newCategory":
            success(request, response, "<string>Category created.</string>");
            break;
        case "metaWeblog.newPost":
            process(wpRequest);
            success(request, response, String.format("<string>%d</string>", System.currentTimeMillis()));
            break;
        case "metaWeblog.getRecentPosts":
        case "metaWeblog.getCategories":
        default:
            success(request, response, "<array><data></data></array>");
            break;
        }
    }

    public static WordpressPostRequest parse(final InputStream in) throws IOException {
        Document body = null;
        try {
            final DocumentBuilder builder = F.newDocumentBuilder();
            body = builder.parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }

        final WordpressPostRequest wpRequest = new WordpressPostRequest();

        try {
            wpRequest.methodName = (String) METHOD_NAME.evaluate(body, XPathConstants.STRING);

            final NodeList params = (NodeList) PARAM_VALUES.evaluate(body, XPathConstants.NODESET);
            for (int i = 0, l = params.getLength(); i < l; i++) {
                final Node param = params.item(i);
                switch (i) {
                case 0:
                    wpRequest.username = param.getNodeValue();
                    break;
                case 1:
                    wpRequest.password = param.getNodeValue();
                    break;
                default:
                    break;
                }
            }

            final NodeList members = (NodeList) PARAM_MEMBERS.evaluate(body, XPathConstants.NODESET);
            for (int i = 0, l = members.getLength(); i < l; i++) {
                final Node member = members.item(i);

                final String name = (String) MEMBER_NAME.evaluate(member, XPathConstants.STRING);
                switch (name) {
                case "title":
                    wpRequest.title = (String) MEMBER_VALUE.evaluate(member, XPathConstants.STRING);
                    break;
                case "description":
                    wpRequest.description = (String) MEMBER_VALUE.evaluate(member, XPathConstants.STRING);
                    break;
                case "categories":
                    final NodeList values = (NodeList) MEMBER_ARRAY_VALUES.evaluate(member, XPathConstants.NODESET);
                    if (values == null) {
                        wpRequest.categories = new String[0];
                    } else {
                        final String[] categories = new String[values.getLength()];
                        for (int j = 0, jl = values.getLength(); j < jl; j++) {
                            final Node value = values.item(j);
                            categories[j] = value.getNodeValue();
                        }
                        wpRequest.categories = categories;
                    }
                    break;
                case "mt_keywords":
                case "post_status":
                default:
                    break;
                }
            }
            return wpRequest;
        } catch (final XPathExpressionException e) {
            throw new IOException(e);
        }
    }

    protected void success(final HttpServletRequest request, final HttpServletResponse response, final String xml) throws IOException {
        response.getWriter().write(OK.replace("@value@", xml));
    }

    protected void fail(final HttpServletRequest request, final HttpServletResponse response, final Exception e) throws IOException {
        LOGGER.error(e.getLocalizedMessage(), e);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.getWriter().write(FAULT.replace("@faultCode@", "1"));
        request.getSession().invalidate();
    }

    private static class WordpressPostRequest {

        public String methodName;

        public String username;

        public String password;

        public String title;

        public String description;

        public String[] categories = new String[0];

        public URL imageUrl;

        @Override
        public String toString() {
            return "WordpressPostRequest [methodName=" + methodName + ", username=" + username + ", password=" + password + ", title=" + title + ", description=" + description + ", categories=" + Arrays.toString(categories) + ", imageUrl=" + imageUrl + "]";
        }
    }
}
