import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agenda personal en Java Swing (sin librerias externas).
 * Funciones: crear / editar / eliminar eventos, marcar como hecho,
 * filtros (Todos, Hoy, Proximos 7 dias, Pendientes, Hechos), busqueda,
 * categorias, prioridades y guardado automatico en el disco.
 *
 * Los datos se guardan en: <carpeta del usuario>/agenda_datos.txt
 */
public class AgendaApp extends JFrame {

    // ====================== PALETA Y CONSTANTES ======================
    static final Color C_SIDEBAR = new Color(0x1F2937);
    static final Color C_ACCENT  = new Color(0x6366F1);
    static final Color C_BG      = new Color(0xF3F4F6);
    static final Color C_TEXT    = new Color(0x111827);
    static final Color C_MUTED   = new Color(0x6B7280);
    static final Color C_OK      = new Color(0x10B981);
    static final Color C_DANGER  = new Color(0xEF4444);
    static final Color C_WARN    = new Color(0xF59E0B);

    static final String[] CATEGORIAS  = {"Personal", "Trabajo", "Estudio", "Salud", "Otro"};
    static final String[] PRIORIDADES = {"Alta", "Media", "Baja"};

    static final Locale ES = Locale.forLanguageTag("es-CL");
    static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("EEE d MMM yyyy", ES);
    static final DateTimeFormatter FMT_HORA  = DateTimeFormatter.ofPattern("HH:mm");
    static final Path ARCHIVO = Paths.get(System.getProperty("user.home"), "agenda_datos.txt");

    static Color colorCategoria(String c) {
        switch (c) {
            case "Personal": return new Color(0x8B5CF6);
            case "Trabajo":  return new Color(0x3B82F6);
            case "Estudio":  return new Color(0x0EA5E9);
            case "Salud":    return new Color(0x10B981);
            default:         return new Color(0x6B7280);
        }
    }

    static Color colorPrioridad(String p) {
        switch (p) {
            case "Alta":  return C_DANGER;
            case "Media": return C_WARN;
            default:      return C_OK;
        }
    }

    static Font fuente(int estilo, int tam) {
        return new Font("Segoe UI", estilo, tam);
    }

    // ====================== MODELO ======================
    static class Evento {
        String titulo, descripcion, categoria, prioridad;
        LocalDate fecha;
        LocalTime hora;
        boolean hecho;

        Evento(String titulo, String descripcion, String categoria, String prioridad,
               LocalDate fecha, LocalTime hora, boolean hecho) {
            this.titulo = titulo;
            this.descripcion = descripcion;
            this.categoria = categoria;
            this.prioridad = prioridad;
            this.fecha = fecha;
            this.hora = hora;
            this.hecho = hecho;
        }

        LocalDateTime cuando() {
            return LocalDateTime.of(fecha, hora);
        }

        boolean vencido() {
            return !hecho && cuando().isBefore(LocalDateTime.now());
        }
    }

    enum Filtro {
        TODOS("Todos"), HOY("Hoy"), SEMANA("Próximos 7 días"),
        PENDIENTES("Pendientes"), HECHOS("Completados");

        final String titulo;

        Filtro(String titulo) {
            this.titulo = titulo;
        }

        boolean cumple(Evento e) {
            LocalDate hoy = LocalDate.now();
            switch (this) {
                case HOY:        return e.fecha.equals(hoy);
                case SEMANA:     return !e.fecha.isBefore(hoy) && !e.fecha.isAfter(hoy.plusDays(7));
                case PENDIENTES: return !e.hecho;
                case HECHOS:     return e.hecho;
                default:         return true;
            }
        }
    }

    // ====================== COMPONENTES PERSONALIZADOS ======================
    /** Boton redondeado. Si nav=true se comporta como item de menu lateral. */
    static class Boton extends JButton {
        private final Color fondo;
        private final boolean nav;
        private boolean seleccionado, hover;

        Boton(String texto, Color fondo, Color letra, boolean nav) {
            super(texto);
            this.fondo = fondo;
            this.nav = nav;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(letra);
            setFont(fuente(Font.BOLD, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (nav) {
                setHorizontalAlignment(SwingConstants.LEFT);
                setBorder(new EmptyBorder(11, 18, 11, 18));
            } else {
                setBorder(new EmptyBorder(10, 20, 10, 20));
            }
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
            });
        }

        void setSeleccionado(boolean s) {
            seleccionado = s;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = null;
            if (nav) {
                if (seleccionado) c = fondo;
                else if (hover) c = new Color(255, 255, 255, 28);
            } else {
                c = hover ? fondo.darker() : fondo;
            }
            if (c != null) {
                g2.setColor(c);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Campo de texto con texto de ayuda (placeholder). */
    static class CampoBusqueda extends JTextField {
        CampoBusqueda(int cols) {
            super(cols);
            setFont(fuente(Font.PLAIN, 14));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0xD1D5DB), 1, true),
                    new EmptyBorder(8, 12, 8, 12)));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(new Color(0x9CA3AF));
                g2.setFont(getFont());
                g2.drawString("Buscar evento...", getInsets().left, getHeight() / 2 + g2.getFontMetrics().getAscent() / 2 - 2);
                g2.dispose();
            }
        }
    }

    /** Dibuja cada evento como una tarjeta. */
    class TarjetaRenderer extends JPanel implements ListCellRenderer<Evento> {
        private Evento ev;
        private boolean sel;

        TarjetaRenderer() {
            setOpaque(false);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Evento> list, Evento value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            this.ev = value;
            this.sel = isSelected;
            return this;
        }

        private String recortar(String s, FontMetrics fm, int max) {
            if (fm.stringWidth(s) <= max) return s;
            while (s.length() > 1 && fm.stringWidth(s + "...") > max) s = s.substring(0, s.length() - 1);
            return s + "...";
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (ev == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int x = 4, y = 5, w = getWidth() - 8, h = getHeight() - 10;

            // Tarjeta
            g2.setColor(sel ? new Color(0xEEF2FF) : Color.WHITE);
            g2.fillRoundRect(x, y, w, h, 18, 18);
            g2.setColor(sel ? C_ACCENT : new Color(0xE5E7EB));
            g2.setStroke(new BasicStroke(sel ? 2f : 1f));
            g2.drawRoundRect(x, y, w, h, 18, 18);

            // Barra de prioridad
            g2.setColor(colorPrioridad(ev.prioridad));
            g2.fillRoundRect(x + 10, y + 14, 5, h - 28, 5, 5);

            // Circulo de "hecho"
            int cx = x + 28, cy = y + h / 2 - 12;
            if (ev.hecho) {
                g2.setColor(C_OK);
                g2.fillOval(cx, cy, 24, 24);
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(cx + 6, cy + 12, cx + 10, cy + 17);
                g2.drawLine(cx + 10, cy + 17, cx + 18, cy + 7);
            } else {
                g2.setColor(new Color(0x9CA3AF));
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(cx, cy, 24, 24);
            }

            // Chip de categoria (derecha)
            g2.setFont(fuente(Font.BOLD, 12));
            FontMetrics fmChip = g2.getFontMetrics();
            int chipW = fmChip.stringWidth(ev.categoria) + 22;
            int chipX = x + w - chipW - 18, chipY = y + 14;
            Color cc = colorCategoria(ev.categoria);
            g2.setColor(new Color(cc.getRed(), cc.getGreen(), cc.getBlue(), 35));
            g2.fillRoundRect(chipX, chipY, chipW, 24, 24, 24);
            g2.setColor(cc);
            g2.drawString(ev.categoria, chipX + 11, chipY + 16);

            // Prioridad (derecha, abajo)
            g2.setFont(fuente(Font.PLAIN, 12));
            FontMetrics fmP = g2.getFontMetrics();
            String tp = "Prioridad " + ev.prioridad.toLowerCase();
            int tpW = fmP.stringWidth(tp);
            int tpX = x + w - tpW - 18, tpY = y + h - 16;
            g2.setColor(C_MUTED);
            g2.drawString(tp, tpX, tpY);
            g2.setColor(colorPrioridad(ev.prioridad));
            g2.fillOval(tpX - 14, tpY - 9, 9, 9);

            int tx = x + 68;
            int maxTexto = chipX - tx - 14;

            // Titulo
            g2.setFont(fuente(Font.BOLD, 16));
            FontMetrics fmT = g2.getFontMetrics();
            String titulo = recortar(ev.titulo, fmT, maxTexto);
            g2.setColor(ev.hecho ? new Color(0x9CA3AF) : C_TEXT);
            g2.drawString(titulo, tx, y + 28);
            if (ev.hecho) {
                int ty = y + 28 - fmT.getAscent() / 3;
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawLine(tx, ty, tx + fmT.stringWidth(titulo), ty);
            }

            // Fecha y hora
            g2.setFont(fuente(Font.PLAIN, 13));
            String fh = ev.fecha.format(FMT_FECHA) + "   ·   " + ev.hora.format(FMT_HORA);
            if (ev.vencido()) {
                fh += "   ·   Vencido";
                g2.setColor(C_DANGER);
            } else {
                g2.setColor(C_MUTED);
            }
            g2.drawString(fh, tx, y + 49);

            // Descripcion
            if (ev.descripcion != null && !ev.descripcion.trim().isEmpty()) {
                g2.setFont(fuente(Font.ITALIC, 12));
                g2.setColor(new Color(0x9CA3AF));
                String d = ev.descripcion.replace('\n', ' ');
                g2.drawString(recortar(d, g2.getFontMetrics(), maxTexto), tx, y + 67);
            }
            g2.dispose();
        }
    }

    // ====================== ESTADO Y VISTA ======================
    private final List<Evento> eventos = new ArrayList<>();
    private final DefaultListModel<Evento> modelo = new DefaultListModel<>();
    private final Map<Filtro, Boton> botonesNav = new EnumMap<>(Filtro.class);
    private Filtro filtroActual = Filtro.TODOS;

    private JList<Evento> lista;
    private CampoBusqueda campoBusqueda;
    private JLabel lblTitulo, lblSubtitulo;
    private Boton btnHecho;

    public AgendaApp() {
        super("Mi Agenda");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1050, 680);
        setMinimumSize(new Dimension(860, 540));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        add(crearSidebar(), BorderLayout.WEST);
        add(crearPanelPrincipal(), BorderLayout.CENTER);

        cargar();
        refrescar();
    }

    private JPanel crearSidebar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(C_SIDEBAR);
        p.setBorder(new EmptyBorder(28, 16, 24, 16));
        p.setPreferredSize(new Dimension(250, 0));

        JLabel logo = new JLabel("Mi Agenda");
        logo.setFont(fuente(Font.BOLD, 26));
        logo.setForeground(Color.WHITE);
        logo.setAlignmentX(Component.LEFT_ALIGNMENT);
        logo.setBorder(new EmptyBorder(0, 8, 0, 0));

        JLabel sub = new JLabel("Organiza tu día");
        sub.setFont(fuente(Font.PLAIN, 13));
        sub.setForeground(new Color(0x9CA3AF));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setBorder(new EmptyBorder(2, 9, 0, 0));

        p.add(logo);
        p.add(sub);
        p.add(Box.createVerticalStrut(32));

        for (final Filtro f : Filtro.values()) {
            Boton b = new Boton(f.titulo, C_ACCENT, Color.WHITE, true);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            b.addActionListener(e -> {
                filtroActual = f;
                refrescar();
            });
            botonesNav.put(f, b);
            p.add(b);
            p.add(Box.createVerticalStrut(6));
        }

        p.add(Box.createVerticalGlue());

        Boton nuevo = new Boton("+  Nuevo evento", C_ACCENT, Color.WHITE, false);
        nuevo.setAlignmentX(Component.LEFT_ALIGNMENT);
        nuevo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        nuevo.addActionListener(e -> nuevoEvento());
        p.add(nuevo);
        return p;
    }

    private JPanel crearPanelPrincipal() {
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBackground(C_BG);
        p.setBorder(new EmptyBorder(26, 30, 22, 30));

        // Encabezado
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JPanel textos = new JPanel();
        textos.setOpaque(false);
        textos.setLayout(new BoxLayout(textos, BoxLayout.Y_AXIS));
        lblTitulo = new JLabel("Todos");
        lblTitulo.setFont(fuente(Font.BOLD, 30));
        lblTitulo.setForeground(C_TEXT);
        lblSubtitulo = new JLabel(" ");
        lblSubtitulo.setFont(fuente(Font.PLAIN, 14));
        lblSubtitulo.setForeground(C_MUTED);
        textos.add(lblTitulo);
        textos.add(lblSubtitulo);

        campoBusqueda = new CampoBusqueda(18);
        campoBusqueda.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { refrescar(); }
            public void removeUpdate(DocumentEvent e)  { refrescar(); }
            public void changedUpdate(DocumentEvent e) { refrescar(); }
        });
        JPanel busqueda = new JPanel(new GridBagLayout());
        busqueda.setOpaque(false);
        busqueda.add(campoBusqueda);

        header.add(textos, BorderLayout.WEST);
        header.add(busqueda, BorderLayout.EAST);

        // Barra de acciones
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        acciones.setOpaque(false);
        Boton btnEditar = new Boton("Editar", new Color(0x4B5563), Color.WHITE, false);
        btnHecho = new Boton("Marcar hecho", C_OK, Color.WHITE, false);
        Boton btnEliminar = new Boton("Eliminar", C_DANGER, Color.WHITE, false);
        btnEditar.addActionListener(e -> editarEvento());
        btnHecho.addActionListener(e -> {
            Evento ev = lista.getSelectedValue();
            if (ev == null) avisarSeleccion(); else alternarHecho(ev);
        });
        btnEliminar.addActionListener(e -> eliminarEvento());
        acciones.add(btnEditar);
        acciones.add(btnHecho);
        acciones.add(btnEliminar);

        JPanel norte = new JPanel(new BorderLayout(0, 16));
        norte.setOpaque(false);
        norte.add(header, BorderLayout.NORTH);
        norte.add(acciones, BorderLayout.SOUTH);
        p.add(norte, BorderLayout.NORTH);

        // Lista de tarjetas
        lista = new JList<Evento>(modelo) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getModel().getSize() == 0) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    String m1 = "No hay eventos aquí";
                    String m2 = "Pulsa \"+ Nuevo evento\" para agregar uno";
                    g2.setFont(fuente(Font.BOLD, 20));
                    g2.setColor(new Color(0x9CA3AF));
                    g2.drawString(m1, (getWidth() - g2.getFontMetrics().stringWidth(m1)) / 2, getHeight() / 2 - 6);
                    g2.setFont(fuente(Font.PLAIN, 14));
                    g2.drawString(m2, (getWidth() - g2.getFontMetrics().stringWidth(m2)) / 2, getHeight() / 2 + 20);
                    g2.dispose();
                }
            }
        };
        lista.setBackground(C_BG);
        lista.setFixedCellHeight(88);
        lista.setCellRenderer(new TarjetaRenderer());
        lista.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lista.addListSelectionListener(e -> actualizarBotonHecho());

        final JPopupMenu menu = new JPopupMenu();
        JMenuItem miEditar = new JMenuItem("Editar");
        JMenuItem miHecho = new JMenuItem("Marcar / desmarcar como hecho");
        JMenuItem miEliminar = new JMenuItem("Eliminar");
        miEditar.addActionListener(e -> editarEvento());
        miHecho.addActionListener(e -> {
            Evento ev = lista.getSelectedValue();
            if (ev != null) alternarHecho(ev);
        });
        miEliminar.addActionListener(e -> eliminarEvento());
        menu.add(miEditar);
        menu.add(miHecho);
        menu.addSeparator();
        menu.add(miEliminar);

        lista.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int i = lista.locationToIndex(e.getPoint());
                Rectangle r = (i >= 0) ? lista.getCellBounds(i, i) : null;
                if (r == null || !r.contains(e.getPoint())) {
                    lista.clearSelection();
                    return;
                }
                lista.setSelectedIndex(i);
                if (SwingUtilities.isRightMouseButton(e)) {
                    menu.show(lista, e.getX(), e.getY());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    int dx = e.getX() - r.x;
                    if (dx >= 24 && dx <= 62) {
                        alternarHecho(modelo.get(i));
                    } else if (e.getClickCount() == 2) {
                        editarEvento();
                    }
                }
            }
        });
        lista.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) eliminarEvento();
            }
        });

        JScrollPane scroll = new JScrollPane(lista);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(C_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ====================== LOGICA ======================
    private void refrescar() {
        Evento seleccionado = (lista != null) ? lista.getSelectedValue() : null;
        String q = (campoBusqueda != null) ? campoBusqueda.getText().trim().toLowerCase() : "";

        List<Evento> visibles = new ArrayList<>();
        for (Evento e : eventos) {
            if (!filtroActual.cumple(e)) continue;
            if (!q.isEmpty()) {
                String texto = (e.titulo + " " + e.descripcion + " " + e.categoria).toLowerCase();
                if (!texto.contains(q)) continue;
            }
            visibles.add(e);
        }
        visibles.sort(Comparator.comparing(Evento::cuando));

        modelo.clear();
        for (Evento e : visibles) modelo.addElement(e);
        if (seleccionado != null && visibles.contains(seleccionado)) {
            lista.setSelectedValue(seleccionado, true);
        }

        // Menu lateral con contadores
        for (Filtro f : Filtro.values()) {
            int n = 0;
            for (Evento e : eventos) if (f.cumple(e)) n++;
            Boton b = botonesNav.get(f);
            b.setText(f.titulo + "  (" + n + ")");
            b.setSeleccionado(f == filtroActual);
        }

        // Encabezado
        lblTitulo.setText(filtroActual.titulo);
        String hoy = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES));
        hoy = Character.toUpperCase(hoy.charAt(0)) + hoy.substring(1);
        lblSubtitulo.setText(hoy + "   ·   " + visibles.size() + (visibles.size() == 1 ? " evento" : " eventos"));
        actualizarBotonHecho();
        lista.repaint();
    }

    private void actualizarBotonHecho() {
        if (btnHecho == null) return;
        Evento ev = lista.getSelectedValue();
        btnHecho.setText(ev != null && ev.hecho ? "Marcar pendiente" : "Marcar hecho");
    }

    private void avisarSeleccion() {
        JOptionPane.showMessageDialog(this, "Primero selecciona un evento de la lista.",
                "Selecciona un evento", JOptionPane.INFORMATION_MESSAGE);
    }

    private void alternarHecho(Evento ev) {
        ev.hecho = !ev.hecho;
        guardar();
        refrescar();
    }

    private void nuevoEvento() {
        Evento nuevo = dialogoEvento(null);
        if (nuevo != null) {
            eventos.add(nuevo);
            guardar();
            refrescar();
            lista.setSelectedValue(nuevo, true);
        }
    }

    private void editarEvento() {
        Evento ev = lista.getSelectedValue();
        if (ev == null) {
            avisarSeleccion();
            return;
        }
        if (dialogoEvento(ev) != null) {
            guardar();
            refrescar();
        }
    }

    private void eliminarEvento() {
        Evento ev = lista.getSelectedValue();
        if (ev == null) {
            avisarSeleccion();
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "¿Eliminar \"" + ev.titulo + "\"?", "Confirmar eliminación",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            eventos.remove(ev);
            guardar();
            refrescar();
        }
    }

    // ====================== DIALOGO NUEVO / EDITAR ======================
    private static Date aDate(LocalDate d, LocalTime t) {
        return Date.from(d.atTime(t).atZone(ZoneId.systemDefault()).toInstant());
    }

    private Evento dialogoEvento(final Evento base) {
        final JDialog d = new JDialog(this, base == null ? "Nuevo evento" : "Editar evento", true);

        final JTextField tTitulo = new JTextField(24);
        final JTextArea tDesc = new JTextArea(4, 24);
        tDesc.setLineWrap(true);
        tDesc.setWrapStyleWord(true);
        JScrollPane spDesc = new JScrollPane(tDesc);

        final JSpinner sFecha = new JSpinner(new SpinnerDateModel());
        sFecha.setEditor(new JSpinner.DateEditor(sFecha, "dd/MM/yyyy"));
        final JSpinner sHora = new JSpinner(new SpinnerDateModel());
        sHora.setEditor(new JSpinner.DateEditor(sHora, "HH:mm"));
        final JComboBox<String> cCat = new JComboBox<>(CATEGORIAS);
        final JComboBox<String> cPri = new JComboBox<>(PRIORIDADES);

        if (base != null) {
            tTitulo.setText(base.titulo);
            tDesc.setText(base.descripcion);
            sFecha.setValue(aDate(base.fecha, base.hora));
            sHora.setValue(aDate(base.fecha, base.hora));
            cCat.setSelectedItem(base.categoria);
            cPri.setSelectedItem(base.prioridad);
        } else {
            LocalTime proxima = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0);
            sFecha.setValue(aDate(LocalDate.now(), proxima));
            sHora.setValue(aDate(LocalDate.now(), proxima));
            cPri.setSelectedItem("Media");
        }

        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(new EmptyBorder(22, 26, 18, 26));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 4, 6, 4);
        gc.anchor = GridBagConstraints.WEST;
        gc.fill = GridBagConstraints.HORIZONTAL;

        JLabel cab = new JLabel(base == null ? "Nuevo evento" : "Editar evento");
        cab.setFont(fuente(Font.BOLD, 22));
        cab.setForeground(C_TEXT);
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        p.add(cab, gc);
        gc.gridwidth = 1;

        String[] etiquetas = {"Título *", "Descripción", "Fecha", "Hora", "Categoría", "Prioridad"};
        Component[] campos = {tTitulo, spDesc, sFecha, sHora, cCat, cPri};
        for (int i = 0; i < etiquetas.length; i++) {
            JLabel l = new JLabel(etiquetas[i]);
            l.setFont(fuente(Font.BOLD, 13));
            l.setForeground(C_MUTED);
            gc.gridx = 0; gc.gridy = i + 1; gc.weightx = 0;
            gc.anchor = (i == 1) ? GridBagConstraints.NORTHWEST : GridBagConstraints.WEST;
            p.add(l, gc);
            gc.gridx = 1; gc.weightx = 1;
            p.add(campos[i], gc);
        }

        final Evento[] resultado = new Evento[1];
        Boton btnCancelar = new Boton("Cancelar", new Color(0xE5E7EB), C_TEXT, false);
        Boton btnGuardar = new Boton("Guardar", C_ACCENT, Color.WHITE, false);
        btnCancelar.addActionListener(e -> d.dispose());
        btnGuardar.addActionListener(e -> {
            String titulo = tTitulo.getText().trim();
            if (titulo.isEmpty()) {
                JOptionPane.showMessageDialog(d, "El título es obligatorio.", "Falta el título",
                        JOptionPane.WARNING_MESSAGE);
                tTitulo.requestFocus();
                return;
            }
            ZoneId zona = ZoneId.systemDefault();
            LocalDate fecha = ((Date) sFecha.getValue()).toInstant().atZone(zona).toLocalDate();
            LocalTime hora = ((Date) sHora.getValue()).toInstant().atZone(zona).toLocalTime()
                    .withSecond(0).withNano(0);
            String desc = tDesc.getText().trim();
            String cat = (String) cCat.getSelectedItem();
            String pri = (String) cPri.getSelectedItem();
            if (base != null) {
                base.titulo = titulo;
                base.descripcion = desc;
                base.fecha = fecha;
                base.hora = hora;
                base.categoria = cat;
                base.prioridad = pri;
                resultado[0] = base;
            } else {
                resultado[0] = new Evento(titulo, desc, cat, pri, fecha, hora, false);
            }
            d.dispose();
        });

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        botones.setOpaque(false);
        botones.add(btnCancelar);
        botones.add(btnGuardar);
        gc.gridx = 0; gc.gridy = etiquetas.length + 1; gc.gridwidth = 2;
        gc.insets = new Insets(18, 4, 0, 4);
        p.add(botones, gc);

        d.setContentPane(p);
        d.getRootPane().setDefaultButton(btnGuardar);
        d.pack();
        d.setResizable(false);
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        return resultado[0];
    }

    // ====================== PERSISTENCIA ======================
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void guardar() {
        List<String> lineas = new ArrayList<>();
        for (Evento e : eventos) {
            lineas.add(esc(e.titulo) + "\t" + esc(e.descripcion) + "\t" + e.categoria + "\t"
                    + e.prioridad + "\t" + e.fecha + "\t" + e.hora + "\t" + e.hecho);
        }
        try {
            Files.write(ARCHIVO, lineas, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar la agenda:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cargar() {
        if (!Files.exists(ARCHIVO)) {
            // Datos de ejemplo la primera vez
            LocalDate hoy = LocalDate.now();
            eventos.add(new Evento("Bienvenido a tu agenda", "Doble clic para editar, clic en el círculo para completar.",
                    "Personal", "Media", hoy, LocalTime.of(9, 0), false));
            eventos.add(new Evento("Reunión de equipo", "Revisar avances de la semana",
                    "Trabajo", "Alta", hoy, LocalTime.of(15, 30), false));
            eventos.add(new Evento("Estudiar para la prueba", "Repasar apuntes y ejercicios",
                    "Estudio", "Alta", hoy.plusDays(2), LocalTime.of(18, 0), false));
            eventos.add(new Evento("Control médico", "", "Salud", "Baja",
                    hoy.plusDays(5), LocalTime.of(11, 0), false));
            guardar();
            return;
        }
        try {
            for (String linea : Files.readAllLines(ARCHIVO, StandardCharsets.UTF_8)) {
                if (linea.trim().isEmpty()) continue;
                String[] c = linea.split("\t", -1);
                if (c.length < 7) continue;
                try {
                    eventos.add(new Evento(unesc(c[0]), unesc(c[1]), c[2], c[3],
                            LocalDate.parse(c[4]), LocalTime.parse(c[5]), Boolean.parseBoolean(c[6])));
                } catch (DateTimeParseException ignorada) {
                    // linea danada: se omite
                }
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo leer la agenda:\n" + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ====================== MAIN ======================
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorada) {
            // se usa el aspecto por defecto
        }
        SwingUtilities.invokeLater(() -> new AgendaApp().setVisible(true));
    }
}
